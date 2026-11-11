package com.dugcanlift.liftmap

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.util.UUID

/** One downloaded area, as the Maps screens list it. */
data class StreetArea(
    val id: UUID,
    val name: String,
    val detail: MapBounds,
    val detailKm: Int,
    val downloadedAt: Instant,
    val bytes: Long,
)

/**
 * Every downloaded area's vector tiles in ONE MBTiles file, which MapLibre
 * reads through `mbtiles://` with its own SQLite. `areas` and `area_tiles` are
 * ours; MapLibre ignores them. `area_tiles` holds XYZ, `tiles` holds TMS rows.
 * The same schema as the Swift kit's `StreetStore`.
 *
 * One connection, every call serialised on it, so the download's four workers
 * and the screens can share a store.
 *
 * **Writes pause maps, by handshake.** Every write goes through a writer that
 * raises the [writing] gate first, then waits for every open map to close
 * ([awaitMapsClosed]: [mapsOpen] back to 0), and only then writes: a download
 * or resume ([MapDownload]) and [removeArea]. A `RouteMap` draws the plain line
 * while the gate is up, which disposes its MapView, and counts itself closed
 * only after the MapView's `onDestroy`. Nothing else may write, so the writing
 * calls are internal and [standard] is the only way an app gets a store.
 */
class StreetStore internal constructor(val file: File) {
    /** A tile was offered for an area with no `areas` row: never begun, or deleted. */
    class UnknownArea(val id: UUID) : Exception("No street area $id")

    private val lock = Any()
    private val db: SQLiteConnection

    private val _revision = MutableStateFlow(0)

    /**
     * Goes up by one whenever the set of finished areas changes ([finishArea],
     * [deleteArea]), so a map on screen can ask [covers] again: a finished
     * download shows its streets, a deleted area falls back to the line.
     */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /**
     * The store's own scope, as long-lived as the store (one per process, from
     * [standard]): work the person asked for -- a delete, a cancelled
     * download's cleanup -- runs here, so it finishes even if the screen that
     * started it goes away while it waits for the maps.
     */
    internal val work = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> logWarning("Street map work failed", e) },
    )

    private val gateLock = Any()
    private var writers = 0
    private var maps = 0
    private val _writing = MutableStateFlow(false)
    private val _mapsOpen = MutableStateFlow(0)

    /**
     * The write gate: true from the moment a writer starts (a download or a
     * resume, from before beginning its area to finishing, failing or being
     * cancelled, cleanup included, and a [removeArea]) until it ends. A writer
     * raises it, then waits for [mapsOpen] to reach 0 ([awaitMapsClosed]), and
     * only then writes. `RouteMap` draws the plain line while it is true, which
     * disposes its MapView, and draws the map again when it is false. On
     * Android this handshake is the only thing keeping them apart: this store
     * writes through androidx's bundled SQLite and MapLibre reads through its
     * own native SQLite, two libraries in one process, and POSIX file locks do
     * not exclude each other within a process. `busy_timeout` only waits for
     * readers that use this store's own SQLite (another process, a tool); it
     * does nothing between this store and MapLibre.
     */
    val writing: StateFlow<Boolean> = _writing.asStateFlow()

    /**
     * How many MapViews drawing from this store are alive: raised when one is
     * made, lowered only after its `onDestroy`. A writer waits for 0.
     */
    val mapsOpen: StateFlow<Int> = _mapsOpen.asStateFlow()

    /**
     * Counts a map open, unless a writer holds the gate: then it is refused
     * (false), not counted, and needs no [mapClosed], and the map must not make
     * a MapView. The check and the count share the gate's lock, so once a
     * writer has raised the gate and seen no maps, no map can open behind it.
     */
    internal fun mapOpened(): Boolean = synchronized(gateLock) {
        if (writers > 0) return false
        maps += 1
        _mapsOpen.value = maps
        true
    }

    internal fun mapClosed() {
        synchronized(gateLock) {
            check(maps > 0) { "mapClosed without mapOpened" }
            maps -= 1
            _mapsOpen.value = maps
        }
    }

    /**
     * Waits until no map is open, or [timeoutMs] has passed, and returns either
     * way, so a stuck map cannot stop a download for ever. After a timeout
     * nothing keeps the writer and that map apart (`busy_timeout` cannot: the
     * two use different SQLite libraries), which is why the timeout is only
     * for a map that never closes, and why the on-device check matters. Call it
     * with the gate already raised: from then on no new map can open.
     */
    suspend fun awaitMapsClosed(timeoutMs: Long = 2_000) {
        if (mapsOpen.value == 0) return
        withTimeoutOrNull(timeoutMs) { mapsOpen.first { it == 0 } }
    }

    /** Opens the write gate; gates nest, and each must be ended by [endWriting], in a `finally`. */
    internal fun beginWriting() {
        synchronized(gateLock) {
            writers += 1
            _writing.value = true
        }
    }

    internal fun endWriting() {
        synchronized(gateLock) {
            check(writers > 0) { "endWriting without beginWriting" }
            writers -= 1
            _writing.value = writers > 0
        }
    }

    /** Runs [body] with the write gate held. */
    internal inline fun <T> whileWriting(body: () -> T): T {
        beginWriting()
        try {
            return body()
        } finally {
            endWriting()
        }
    }

    init {
        file.parentFile?.mkdirs()
        db = BundledSQLiteDriver().open(file.path)
        try {
            // In DELETE mode a COMMIT needs the file to itself: wait up to 5 s for
            // a reader that shares this SQLite (another process, a tool) rather
            // than fail with SQLITE_BUSY. It does nothing about MapLibre, whose
            // own SQLite library this one cannot see; the gate handles that.
            db.prepare("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS").use { it.step() }
            // MapLibre opens the file read-only; a WAL would need write access to -shm.
            db.prepare("PRAGMA journal_mode = DELETE").use { it.step() }
            transaction {
                db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS tiles (
                    zoom_level INT, tile_column INT, tile_row INT, tile_data BLOB,
                    PRIMARY KEY (zoom_level, tile_column, tile_row))""",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS areas (
                    id TEXT PRIMARY KEY, name TEXT, south REAL, west REAL, north REAL, east REAL,
                    detail_km INT, started_at REAL, finished_at REAL, bytes INT)""",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS area_tiles (
                    area_id TEXT, z INT, x INT, y INT, PRIMARY KEY (area_id, z, x, y))""",
                )
                // deleteArea keeps a tile while any area still owns it, a lookup by
                // (z, x, y) the primary key (led by area_id) cannot answer without a
                // scan per tile. A store written before this gains it on open.
                db.execSQL("CREATE INDEX IF NOT EXISTS area_tiles_zxy ON area_tiles (z, x, y)")
                val meta = listOf(
                    "name" to "LIFT streets", "format" to "pbf", "minzoom" to "0", "maxzoom" to "14",
                    "attribution" to ATTRIBUTION,
                )
                for ((k, v) in meta) {
                    db.prepare("INSERT OR REPLACE INTO metadata (name, value) VALUES (?, ?)").use {
                        it.bindText(1, k); it.bindText(2, v); it.step()
                    }
                }
            }
        } catch (e: Exception) {
            db.close()
            throw e
        }
    }

    private fun <T> transaction(body: () -> T): T = synchronized(lock) {
        db.execSQL("BEGIN IMMEDIATE")
        try {
            val result = body()
            db.execSQL("COMMIT")
            result
        } catch (e: Throwable) {
            runCatching { db.execSQL("ROLLBACK") }
            throw e
        }
    }

    private fun <T> read(body: () -> T): T = synchronized(lock) { body() }

    private fun now() = System.currentTimeMillis() / 1000.0

    private fun key(id: UUID) = id.toString().uppercase()

    internal fun beginArea(plan: StreetAreaPlan, name: String): UUID {
        val id = UUID.randomUUID()
        transaction {
            db.prepare(
                """INSERT INTO areas (id, name, south, west, north, east, detail_km, started_at, finished_at, bytes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 0)""",
            ).use {
                it.bindText(1, key(id)); it.bindText(2, name)
                it.bindDouble(3, plan.detail.south); it.bindDouble(4, plan.detail.west)
                it.bindDouble(5, plan.detail.north); it.bindDouble(6, plan.detail.east)
                it.bindLong(7, plan.detailKm.toLong()); it.bindDouble(8, now())
                it.step()
            }
        }
        return id
    }

    /**
     * Stores one tile for [area]. Refuses an area with no row ([UnknownArea]),
     * so a download still running after its area was deleted writes nothing.
     */
    internal fun put(data: ByteArray, tile: TileID, area: UUID) {
        transaction {
            val known = db.prepare("SELECT 1 FROM areas WHERE id = ?").use { it.bindText(1, key(area)); it.step() }
            if (!known) throw UnknownArea(area)
            db.prepare("INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)").use {
                it.bindLong(1, tile.z.toLong()); it.bindLong(2, tile.x.toLong()); it.bindLong(3, tile.tmsRow.toLong())
                it.bindBlob(4, data)
                it.step()
            }
            db.prepare("INSERT OR IGNORE INTO area_tiles (area_id, z, x, y) VALUES (?, ?, ?, ?)").use {
                it.bindText(1, key(area)); it.bindLong(2, tile.z.toLong()); it.bindLong(3, tile.x.toLong())
                it.bindLong(4, tile.y.toLong())
                it.step()
            }
        }
    }

    /** True while [id] has a row, finished or not. */
    fun hasArea(id: UUID): Boolean = read {
        db.prepare("SELECT 1 FROM areas WHERE id = ?").use { it.bindText(1, key(id)); it.step() }
    }

    fun hasTile(tile: TileID, area: UUID): Boolean = read {
        db.prepare("SELECT 1 FROM area_tiles WHERE area_id = ? AND z = ? AND x = ? AND y = ?").use {
            it.bindText(1, key(area)); it.bindLong(2, tile.z.toLong()); it.bindLong(3, tile.x.toLong())
            it.bindLong(4, tile.y.toLong())
            it.step()
        }
    }

    internal fun finishArea(id: UUID) {
        transaction {
            val bytes = db.prepare(
                """SELECT COALESCE(SUM(LENGTH(t.tile_data)), 0) FROM area_tiles a
                JOIN tiles t ON t.zoom_level = a.z AND t.tile_column = a.x
                    AND t.tile_row = ((1 << a.z) - 1 - a.y)
                WHERE a.area_id = ?""",
            ).use { it.bindText(1, key(id)); if (it.step()) it.getLong(0) else 0L }
            db.prepare("UPDATE areas SET finished_at = ?, bytes = ? WHERE id = ?").use {
                it.bindDouble(1, now()); it.bindLong(2, bytes); it.bindText(3, key(id)); it.step()
            }
        }
        _revision.update()
    }

    /** Finished areas, newest first. */
    fun areas(): List<StreetArea> = list(finished = true)

    /**
     * Areas begun and not finished (an interrupted download), newest first;
     * `downloadedAt` is when each began. [MapDownload.resume] takes one of these.
     */
    fun unfinishedAreas(): List<StreetArea> = list(finished = false)

    private fun list(finished: Boolean): List<StreetArea> = read {
        val sql = if (finished) {
            "SELECT id, name, south, west, north, east, detail_km, finished_at, bytes FROM areas WHERE finished_at IS NOT NULL ORDER BY finished_at DESC"
        } else {
            "SELECT id, name, south, west, north, east, detail_km, started_at, bytes FROM areas WHERE finished_at IS NULL ORDER BY started_at DESC"
        }
        db.prepare(sql).use { st ->
            val out = mutableListOf<StreetArea>()
            while (st.step()) area(st)?.let { out += it }
            out
        }
    }

    private fun area(st: SQLiteStatement): StreetArea? {
        val id = runCatching { UUID.fromString(st.getText(0)) }.getOrNull() ?: return null
        val at = st.getDouble(7)
        return StreetArea(
            id = id,
            name = if (st.isNull(1)) "" else st.getText(1),
            detail = MapBounds(south = st.getDouble(2), west = st.getDouble(3), north = st.getDouble(4), east = st.getDouble(5)),
            detailKm = st.getLong(6).toInt(),
            downloadedAt = Instant.ofEpochMilli((at * 1000).toLong()),
            bytes = st.getLong(8),
        )
    }

    /**
     * Deletes [id] and every tile no other area still owns, once no map is
     * reading the file: raises the gate, waits for the maps to close, deletes
     * off the main thread, and lowers the gate however it ends. The work runs
     * on the store's own scope, so a caller that goes away (a screen left
     * while it waits) only stops watching: the delete still happens. Returns
     * when it is done, or throws what the delete threw.
     */
    suspend fun removeArea(id: UUID) {
        work.async {
            beginWriting()
            try {
                awaitMapsClosed()
                deleteArea(id)
            } finally {
                endWriting()
            }
        }.await()
    }

    /** The delete itself, synchronous; [removeArea] is the way in. */
    internal fun deleteArea(id: UUID) = whileWriting {
        transaction {
            db.prepare("DELETE FROM area_tiles WHERE area_id = ?").use { it.bindText(1, key(id)); it.step() }
            db.execSQL(
                """DELETE FROM tiles WHERE NOT EXISTS (
                    SELECT 1 FROM area_tiles a
                    WHERE a.z = zoom_level AND a.x = tile_column AND a.y = ((1 << zoom_level) - 1 - tile_row))""",
            )
            db.prepare("DELETE FROM areas WHERE id = ?").use { it.bindText(1, key(id)); it.step() }
        }
        _revision.update()
    }

    /** True when any finished area's detail box touches [bounds]. Partial cover is cover. */
    fun covers(bounds: MapBounds): Boolean = areas().any { it.detail.intersects(bounds) }

    /**
     * Closes the file and stops the store's own work. Internal: the store is
     * one per process, shared by every screen, so no screen may close it.
     */
    internal fun close() {
        synchronized(opened) { if (opened[file.path] === this) opened.remove(file.path) }
        work.cancel()
        synchronized(lock) { db.close() }
    }

    private fun MutableStateFlow<Int>.update() {
        synchronized(this) { value += 1 }
    }

    companion object {
        /** OpenStreetMap's licence asks for the first half; OpenFreeMap is credited beside it. */
        const val ATTRIBUTION = "© OpenStreetMap contributors · OpenFreeMap"

        /** How long a write waits for a reader (MapLibre) to let go of the file. */
        internal const val BUSY_TIMEOUT_MS = 5_000

        private val opened = mutableMapOf<String, StreetStore>()

        /**
         * The app's one store, `filesDir/liftmap/streets.mbtiles`, opened once
         * per process. Each app excludes it from Android backup in its own
         * `data_extraction_rules.xml`: the streets can be downloaded again.
         */
        fun standard(ctx: Context): StreetStore {
            val file = File(File(ctx.applicationContext.filesDir, "liftmap"), "streets.mbtiles")
            return synchronized(opened) { opened.getOrPut(file.path) { StreetStore(file) } }
        }
    }
}

/**
 * Logs without ever throwing: `android.util.Log` is a stub on the JVM in unit
 * tests. Plain `Log`, not Timber: Timber is MapLibre's dependency, not ours.
 */
@android.annotation.SuppressLint("LogNotTimber")
internal fun logWarning(message: String, e: Throwable) {
    runCatching { android.util.Log.w("LiftMap", message, e) }
}
