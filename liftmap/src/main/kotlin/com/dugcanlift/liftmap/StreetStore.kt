package com.dugcanlift.liftmap

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
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
 */
class StreetStore(val file: File) : Closeable {
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

    private val gateLock = Any()
    private var writers = 0
    private val _writing = MutableStateFlow(false)

    /**
     * True while anything is writing the file: a download or a resume, from
     * beginning its area to finishing, failing or being cancelled (cleanup
     * included), and a [deleteArea]. **Writes pause maps**: `RouteMap` draws
     * the plain line while this is true, which disposes its MapView so
     * MapLibre stops reading the file, and draws the map again when it is
     * false. On Android this is the only thing keeping them apart: this store
     * writes through androidx's bundled SQLite and MapLibre reads through its
     * own native SQLite, two libraries in one process, and POSIX file locks do
     * not exclude each other within a process. `busy_timeout` stays as a
     * second line of defence. A map only sees this gate if it uses the same
     * store, which is why apps use [standard].
     */
    val writing: StateFlow<Boolean> = _writing.asStateFlow()

    /** Opens the write gate; gates nest, and each must be ended by [endWriting], in a `finally`. */
    fun beginWriting() {
        synchronized(gateLock) {
            writers += 1
            _writing.value = true
        }
    }

    fun endWriting() {
        synchronized(gateLock) {
            check(writers > 0) { "endWriting without beginWriting" }
            writers -= 1
            _writing.value = writers > 0
        }
    }

    /** Runs [body] with the write gate held. */
    inline fun <T> whileWriting(body: () -> T): T {
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
            // MapLibre's tile reader holds read locks while it draws, and in
            // DELETE mode a COMMIT needs the file to itself: wait up to 5 s for
            // a reader to finish rather than fail with SQLITE_BUSY.
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

    fun beginArea(plan: StreetAreaPlan, name: String): UUID {
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
    fun put(data: ByteArray, tile: TileID, area: UUID) {
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

    fun finishArea(id: UUID) {
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

    /** Deletes the area and every tile no other area still owns, with the write gate held. */
    fun deleteArea(id: UUID) = whileWriting {
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

    override fun close() {
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
