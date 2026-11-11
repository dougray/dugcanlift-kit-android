package com.dugcanlift.liftmap

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.UUID

/** Ported from the Swift kit's `StreetStoreTests`, case for case, plus the revision and orphan rules. */
class StreetStoreTest {
    private val files = mutableListOf<File>()

    @After fun tearDown() {
        files.forEach { f -> listOf("", "-journal", "-wal", "-shm").forEach { File(f.path + it).delete() } }
    }

    private fun makeStore(): StreetStore {
        val f = File(System.getProperty("java.io.tmpdir"), "streets-${UUID.randomUUID()}.mbtiles")
        files += f
        return StreetStore(f)
    }

    private fun plan(lat: Double = 39.7392, lon: Double = -104.9903) = StreetAreaPlan(lat, lon, 10)

    /** Reads the file through a connection of its own, as MapLibre does. */
    private fun <T> query(store: StreetStore, sql: String, read: (androidx.sqlite.SQLiteStatement) -> T): T {
        val c = BundledSQLiteDriver().open(store.file.path)
        try {
            return c.prepare(sql).use { st -> st.step(); read(st) }
        } finally {
            c.close()
        }
    }

    private fun count(store: StreetStore, sql: String): Long = query(store, sql) { it.getLong(0) }

    @Test fun aTileIsStoredInTMSRows() {
        val store = makeStore()
        val id = store.beginArea(plan(), "Denver")
        store.put(byteArrayOf(1, 2, 3), TileID(14, 3413, 6218), id)
        assertEquals(1L, count(store, "SELECT COUNT(*) FROM tiles WHERE zoom_level = 14 AND tile_column = 3413 AND tile_row = 10165"))
    }

    @Test fun anUnfinishedAreaIsListedSeparately() {
        val store = makeStore()
        val id = store.beginArea(plan(), "A")
        assertEquals(0, store.areas().size)
        assertEquals(listOf(id), store.unfinishedAreas().map { it.id })
        store.finishArea(id)
        assertEquals(0, store.unfinishedAreas().size)
    }

    @Test fun deletingOneAreaKeepsTilesAnotherStillOwns() {
        val store = makeStore()
        val a = store.beginArea(plan(), "A")
        val b = store.beginArea(plan(), "B")
        val tile = TileID(5, 6, 12)
        store.put(byteArrayOf(9), tile, a)
        store.put(byteArrayOf(9), tile, b)
        store.deleteArea(a)
        assertEquals(1L, count(store, "SELECT COUNT(*) FROM tiles"))
        store.deleteArea(b)
        assertEquals(0L, count(store, "SELECT COUNT(*) FROM tiles"))
    }

    @Test fun anUnfinishedDownloadIsNotAnArea() {
        val store = makeStore()
        val id = store.beginArea(plan(), "Half")
        store.put(byteArrayOf(1), TileID(3, 1, 2), id)
        assertTrue(store.areas().isEmpty())
        store.deleteArea(id)
        assertEquals(0L, count(store, "SELECT COUNT(*) FROM tiles"))
        assertEquals(0L, count(store, "SELECT COUNT(*) FROM areas"))
    }

    @Test fun coversIsAnyFinishedAreaTouchingTheBounds() {
        val store = makeStore()
        val p = plan()
        val done = store.beginArea(p, "Denver")
        store.finishArea(done)
        val d = p.detail
        assertTrue(store.covers(MapBounds(d.south + 0.01, d.west + 0.01, d.south + 0.02, d.west + 0.02)))
        assertTrue(store.covers(MapBounds(d.north - 0.01, d.east - 0.01, d.north + 1, d.east + 1)))
        assertFalse(store.covers(MapBounds(10.0, 10.0, 11.0, 11.0)))
        val other = plan(51.5, -0.12)
        store.beginArea(other, "London")
        assertFalse(store.covers(other.detail))
    }

    @Test fun metadataIsWhatMapLibreReads() {
        val store = makeStore()
        assertEquals("pbf", query(store, "SELECT value FROM metadata WHERE name = 'format'") { it.getText(0) })
        assertEquals("14", query(store, "SELECT value FROM metadata WHERE name = 'maxzoom'") { it.getText(0) })
        assertEquals("delete", query(store, "PRAGMA journal_mode") { it.getText(0) }.lowercase())
    }

    @Test fun resumingSkipsTilesAlreadyStored() {
        val store = makeStore()
        val id = store.beginArea(plan(), "Denver")
        val tile = TileID(12, 853, 1554)
        assertFalse(store.hasTile(tile, id))
        store.put(byteArrayOf(1, 2), tile, id)
        assertTrue(store.hasTile(tile, id))
        store.finishArea(id)
        val area = store.areas().first()
        assertEquals(2L, area.bytes)
        assertEquals("Denver", area.name)
    }

    @Test fun finishingAndDeletingAnAreaEachBumpTheRevision() {
        val store = makeStore()
        val start = store.revision.value
        val id = store.beginArea(plan(), "Denver")
        store.put(byteArrayOf(1), TileID(3, 1, 2), id)
        assertEquals("beginning and filling an area changes no map", start, store.revision.value)
        store.finishArea(id)
        assertEquals(start + 1, store.revision.value)
        store.deleteArea(id)
        assertEquals(start + 2, store.revision.value)
    }

    @Test fun aTileForAnAreaWithNoRowIsRefused() {
        val store = makeStore()
        try {
            store.put(byteArrayOf(1), TileID(3, 1, 2), UUID.randomUUID())
            fail("expected UnknownArea")
        } catch (_: StreetStore.UnknownArea) {
        }
        val gone = store.beginArea(plan(), "Gone")
        store.deleteArea(gone)
        try {
            store.put(byteArrayOf(1), TileID(3, 1, 2), gone)
            fail("expected UnknownArea for a deleted area")
        } catch (_: StreetStore.UnknownArea) {
        }
        assertEquals(0L, count(store, "SELECT COUNT(*) FROM area_tiles"))
        assertEquals(0L, count(store, "SELECT COUNT(*) FROM tiles"))
    }

    @Test fun aStoreOpensAgainWithItsAreas() {
        val store = makeStore()
        val id = store.beginArea(plan(), "Denver")
        store.finishArea(id)
        store.close()
        val again = StreetStore(store.file)
        assertEquals(listOf(id), again.areas().map { it.id })
        again.close()
    }
}
