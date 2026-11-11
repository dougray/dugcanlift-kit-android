package com.dugcanlift.liftmap

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.UUID

/**
 * Serves a TileJSON and tiles without a network: `MapDownload`'s `http` is
 * handed this instead of `URL.openConnection`. Every connection made is kept,
 * with the headers the download set on it.
 */
class FakeHttp {
    data class Reply(val status: Int, val body: ByteArray = ByteArray(0), val location: String? = null)

    @Volatile var handler: (URL) -> Reply = { Reply(500) }
    val made: MutableList<FakeConnection> = Collections.synchronizedList(mutableListOf())
    val urls: List<URL> get() = synchronized(made) { made.map { it.url } }

    fun reset(h: (URL) -> Reply) {
        handler = h
        made.clear()
    }

    fun open(url: URL): HttpURLConnection = FakeConnection(url, this).also { made += it }

    class FakeConnection(url: URL, private val owner: FakeHttp) : HttpURLConnection(url) {
        private val reply by lazy { owner.handler(url) }
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = reply.status
        override fun getHeaderField(name: String?): String? =
            if (name.equals("Location", ignoreCase = true)) reply.location else null
        override fun getInputStream(): InputStream =
            if (reply.status in 200..299) ByteArrayInputStream(reply.body) else throw java.io.IOException("HTTP ${reply.status}")
        override fun getErrorStream(): InputStream? = if (reply.status >= 400) ByteArrayInputStream(reply.body) else null
        val followsRedirects get() = instanceFollowRedirects
        val cachesAllowed get() = useCaches
    }
}

/** Ported from the Swift kit's `MapDownloadTests`, case for case. */
class MapDownloadTest {
    private val files = mutableListOf<File>()
    private val fake = FakeHttp()
    private val plan = StreetAreaPlan(39.7392, -104.9903, 10)

    @After fun tearDown() {
        files.forEach { f -> listOf("", "-journal").forEach { File(f.path + it).delete() } }
    }

    private fun makeStore(): StreetStore {
        val f = File(System.getProperty("java.io.tmpdir"), "dl-${UUID.randomUUID()}.mbtiles")
        files += f
        return StreetStore(f)
    }

    private fun tileJson(template: String) = """{"tiles":["$template"]}""".toByteArray()

    private companion object {
        const val GOOD = "https://tiles.openfreemap.org/planet/v1/{z}/{x}/{y}.pbf"
    }

    private fun download(store: StreetStore) = MapDownload(store, fake::open)

    @Test fun theTemplateMustBeOpenFreeMaps() = runBlocking<Unit> {
        fake.reset { FakeHttp.Reply(200, tileJson("https://evil.example/{z}/{x}/{y}.pbf")) }
        val dl = download(makeStore())
        try {
            dl.download(plan, "X")
            fail("expected RefusedHost")
        } catch (_: MapDownloadException.RefusedHost) {
        }
        assertEquals("no tile may be fetched", 1, fake.urls.size)
    }

    @Test fun anUnfinishedDownloadIsNotAnArea() = runBlocking<Unit> {
        val store = makeStore()
        val bad = plan.tiles[4]
        val badPath = "/planet/v1/${bad.z}/${bad.x}/${bad.y}.pbf"
        fake.reset { url ->
            when (url.path) {
                "/planet" -> FakeHttp.Reply(200, tileJson(GOOD))
                badPath -> FakeHttp.Reply(500)
                else -> FakeHttp.Reply(200, byteArrayOf(1, 2, 3))
            }
        }
        val dl = download(store)
        try {
            dl.download(plan, "X")
            fail("expected a failure")
        } catch (e: MapDownloadException.Http) {
            assertEquals(500, e.status)
        }
        assertEquals(0, store.areas().size)

        // The failed run left exactly one unfinished area, which resume picks up.
        val unfinished = store.unfinishedAreas()
        assertEquals(1, unfinished.size)
        val id = unfinished.first().id
        val stored = plan.tiles.filter { store.hasTile(it, id) }
        fake.reset { url ->
            if (url.path == "/planet") FakeHttp.Reply(200, tileJson(GOOD)) else FakeHttp.Reply(200, byteArrayOf(1, 2, 3))
        }
        val area = dl.resume(id, plan)
        assertEquals(listOf(area.id), store.areas().map { it.id })
        val tilePaths = fake.urls.map { it.path }.filter { it != "/planet" }
        assertEquals(plan.tiles.size - stored.size, tilePaths.size)
        for (t in stored) assertFalse(tilePaths.contains("/planet/v1/${t.z}/${t.x}/${t.y}.pbf"))
    }

    @Test fun aRedirectOffTheHostIsRefused() = runBlocking<Unit> {
        val evil = "https://evil.example/x"
        fake.reset { url ->
            if (url.host == "evil.example") FakeHttp.Reply(200, "{}".toByteArray()) else FakeHttp.Reply(302, location = evil)
        }
        val dl = download(makeStore())
        try {
            dl.tileTemplate()
            fail("expected Http(302)")
        } catch (e: MapDownloadException.Http) {
            assertEquals(302, e.status)
        }
        assertFalse(fake.urls.any { it.host == "evil.example" })

        fake.reset { url ->
            when {
                url.host == "evil.example" -> FakeHttp.Reply(200, byteArrayOf(1))
                url.path == "/planet" -> FakeHttp.Reply(200, tileJson(GOOD))
                else -> FakeHttp.Reply(302, location = "http://tiles.openfreemap.org/downgrade")
            }
        }
        try {
            dl.download(plan, "X")
            fail("expected Http(302)")
        } catch (e: MapDownloadException.Http) {
            assertEquals(302, e.status)
        }
        assertFalse(fake.urls.any { it.host == "evil.example" || it.protocol == "http" })
    }

    @Test fun aRedirectOnTheHostIsFollowed() = runBlocking<Unit> {
        fake.reset { url ->
            when (url.path) {
                "/planet" -> FakeHttp.Reply(302, location = "/planet/latest")
                "/planet/latest" -> FakeHttp.Reply(200, tileJson(GOOD))
                else -> FakeHttp.Reply(500)
            }
        }
        assertEquals(GOOD, download(makeStore()).tileTemplate())
        assertTrue(fake.made.all { !it.followsRedirects })
    }

    @Test fun aMissingTileIsStoredEmptyAndTheAreaFinishes() = runBlocking<Unit> {
        val store = makeStore()
        fake.reset { url ->
            when {
                url.path == "/planet" -> FakeHttp.Reply(200, tileJson(GOOD))
                url.path.endsWith("/0/0/0.pbf") -> FakeHttp.Reply(404)
                else -> FakeHttp.Reply(204)
            }
        }
        val area = download(store).download(plan, "X")
        assertTrue(store.hasTile(plan.tiles[0], area.id))
    }

    @Test fun everyRequestGoesToOneHost() = runBlocking<Unit> {
        fake.reset { url ->
            if (url.path == "/planet") FakeHttp.Reply(200, tileJson(GOOD)) else FakeHttp.Reply(200, byteArrayOf(7))
        }
        download(makeStore()).download(plan, "X")
        val urls = fake.urls
        assertEquals(plan.tiles.size + 1, urls.size)
        for (u in urls) {
            assertEquals("tiles.openfreemap.org", u.host)
            assertEquals("https", u.protocol)
        }
    }

    @Test fun noCookiesAndAPlainUserAgent() = runBlocking<Unit> {
        fake.reset { url ->
            if (url.path == "/planet") FakeHttp.Reply(200, tileJson(GOOD)) else FakeHttp.Reply(200, byteArrayOf(7))
        }
        download(makeStore()).download(plan, "X")
        for (c in fake.made) {
            assertNull(c.getRequestProperty("Cookie"))
            assertEquals("LIFT-maps/1", c.getRequestProperty("User-Agent"))
            assertEquals("GET", c.requestMethod)
            assertFalse(c.cachesAllowed)
        }
    }

    @Test fun progressReachesTotal() = runBlocking<Unit> {
        fake.reset { url ->
            if (url.path == "/planet") FakeHttp.Reply(200, tileJson(GOOD)) else FakeHttp.Reply(200, byteArrayOf(7))
        }
        var last = 0 to 0
        val seen = Collections.synchronizedList(mutableListOf<Int>())
        download(makeStore()).download(plan, "X") { d: Int, t: Int -> last = d to t; seen += d }
        assertEquals(plan.tiles.size, last.first)
        assertEquals(plan.tiles.size, last.second)
        assertEquals("progress never goes backwards", seen.sorted(), seen.toList())
    }

    @Test fun aDownloadIntoADeletedAreaStops() = runBlocking<Unit> {
        val store = makeStore()
        fake.reset { url ->
            if (url.path == "/planet") FakeHttp.Reply(200, tileJson(GOOD)) else FakeHttp.Reply(200, byteArrayOf(7))
        }
        val id = store.beginArea(plan, "X")
        store.deleteArea(id)
        try {
            download(store).resume(id, plan)
            fail("expected UnknownArea")
        } catch (_: StreetStore.UnknownArea) {
        }
        assertTrue(store.areas().isEmpty())
        assertTrue(store.unfinishedAreas().isEmpty())
    }
}
