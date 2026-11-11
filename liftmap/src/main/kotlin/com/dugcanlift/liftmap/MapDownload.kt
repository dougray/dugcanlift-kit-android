package com.dugcanlift.liftmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

sealed class MapDownloadException(message: String) : IOException(message) {
    /** OpenFreeMap's TileJSON did not hold a usable tile template. */
    class BadTemplate : MapDownloadException("The tile template could not be read")

    /** A URL, a template or a redirect named somewhere other than the tile host over https. */
    class RefusedHost : MapDownloadException("Only ${MapDownload.HOST} may be asked")

    /** The host answered with a status that is not a tile; a refused redirect reads as its 3xx. */
    class Http(val status: Int) : MapDownloadException("HTTP $status")
}

/**
 * The one request this module may make, and only when the person starts a
 * street-map download: the TileJSON and the tiles, from [HOST] over https,
 * nothing else. A redirect is followed only when it stays on the host and on
 * https; any other comes back as its 3xx. No cookies are sent, nothing is
 * cached, and the User-Agent is `LIFT-maps/1`, naming neither app nor device.
 *
 * Four tiles are in flight at once. An area is finished only once every tile
 * is stored; a download that stops halfway leaves an unfinished area that
 * [resume] picks up, skipping the tiles already stored. A tile the host does
 * not have (404 or 204: the sea) is stored empty.
 *
 * [http] opens a connection for a URL; tests hand in a fake.
 */
class MapDownload(
    private val store: StreetStore,
    private val http: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    companion object {
        const val HOST = "tiles.openfreemap.org"
        const val USER_AGENT = "LIFT-maps/1"
        internal const val MAX_IN_FLIGHT = 4
        private const val MAX_REDIRECTS = 5
        private const val TIMEOUT_MS = 30_000

        /** [url] when it is https on [HOST] (default port, no user info); otherwise null. */
        internal fun allowed(url: URL?): URL? {
            if (url == null || url.protocol != "https" || url.host != HOST) return null
            if (url.port != -1 && url.port != 443) return null
            if (url.userInfo != null) return null
            return url
        }
    }

    /** One GET, redirects followed by hand so each hop is checked. */
    private fun fetch(start: URL): Pair<Int, ByteArray> {
        var url = allowed(start) ?: throw MapDownloadException.RefusedHost()
        var hops = 0
        while (true) {
            val c = http(url)
            try {
                c.requestMethod = "GET"
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                c.setRequestProperty("User-Agent", USER_AGENT)
                val status = c.responseCode
                if (status in 300..399) {
                    val to = c.getHeaderField("Location")?.let { loc -> runCatching { URL(url, loc) }.getOrNull() }
                    val next = allowed(to)
                    if (next == null || hops >= MAX_REDIRECTS) return status to ByteArray(0)
                    url = next
                    hops++
                    continue
                }
                val body = if (status in 200..299) c.inputStream.use { it.readBytes() } else ByteArray(0)
                return status to body
            } finally {
                c.disconnect()
            }
        }
    }

    /** The versioned tile URL template from OpenFreeMap's TileJSON. */
    suspend fun tileTemplate(): String = withContext(Dispatchers.IO) {
        val (status, data) = fetch(URL("https://$HOST/planet"))
        if (status !in 200..299) throw MapDownloadException.Http(status)
        val first = runCatching {
            JSONObject(data.toString(Charsets.UTF_8)).getJSONArray("tiles").getString(0)
        }.getOrNull()
        if (first == null || "{z}" !in first || "{x}" !in first || "{y}" !in first) {
            throw MapDownloadException.BadTemplate()
        }
        val probe = first.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0")
        val probeURL = try { URL(probe) } catch (_: MalformedURLException) { null }
        allowed(probeURL) ?: throw MapDownloadException.RefusedHost()
        first
    }

    /** Begins a new area for [plan] and fills it. [progress] gets (done, total), never going back. */
    suspend fun download(plan: StreetAreaPlan, name: String, progress: (Int, Int) -> Unit = { _, _ -> }): StreetArea {
        val id = store.beginArea(plan, name)
        return run(id, plan, progress)
    }

    /** Fills an area already begun (by [download] or a sheet), fetching only the tiles it lacks. */
    suspend fun resume(areaId: UUID, plan: StreetAreaPlan, progress: (Int, Int) -> Unit = { _, _ -> }): StreetArea =
        run(areaId, plan, progress)

    private suspend fun run(id: UUID, plan: StreetAreaPlan, progress: (Int, Int) -> Unit): StreetArea =
        withContext(Dispatchers.IO) {
            // An area deleted (a cancel) before this began is not asked for at all.
            if (!store.hasArea(id)) throw StreetStore.UnknownArea(id)
            val template = tileTemplate()
            val total = plan.tiles.size
            val pending = plan.tiles.filterNot { store.hasTile(it, id) }
            val lock = Any()
            var done = total - pending.size
            progress(done, total)

            val next = AtomicInteger(0)
            coroutineScope {
                repeat(minOf(MAX_IN_FLIGHT, pending.size)) {
                    launch {
                        while (true) {
                            val i = next.getAndIncrement()
                            if (i >= pending.size) break
                            ensureActive()
                            fetchTile(pending[i], template, id)
                            synchronized(lock) {
                                done += 1
                                progress(done, total)
                            }
                        }
                    }
                }
            }

            store.finishArea(id)
            store.areas().firstOrNull { it.id == id } ?: throw StreetStore.UnknownArea(id)
        }

    private fun fetchTile(tile: TileID, template: String, area: UUID) {
        val text = template.replace("{z}", tile.z.toString())
            .replace("{x}", tile.x.toString())
            .replace("{y}", tile.y.toString())
        val url = try { URL(text) } catch (_: MalformedURLException) { throw MapDownloadException.BadTemplate() }
        val (status, data) = fetch(url)
        when (status) {
            in 200..299 -> store.put(data, tile, area)
            404, 204 -> store.put(ByteArray(0), tile, area) // the sea: nothing there
            else -> throw MapDownloadException.Http(status)
        }
    }
}
