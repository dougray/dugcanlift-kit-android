package com.dugcanlift.liftmap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.UUID

/**
 * The handshake between writers and maps: a writer raises the gate, then waits
 * for every open map to close (`mapsOpen` back to 0, or a timeout), and only
 * then writes. A gate that rises and falls inside one main-thread call is never
 * seen by a map; the wait is what makes the pause real.
 */
class MapHandshakeTest {
    private val files = mutableListOf<File>()
    private val plan = StreetAreaPlan(39.7392, -104.9903, 10)
    private val good = "https://tiles.openfreemap.org/planet/v1/{z}/{x}/{y}.pbf"

    @After fun tearDown() {
        files.forEach { f -> listOf("", "-journal").forEach { File(f.path + it).delete() } }
    }

    private fun makeStore(): StreetStore {
        val f = File(System.getProperty("java.io.tmpdir"), "hs-${UUID.randomUUID()}.mbtiles")
        files += f
        return StreetStore(f)
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    @Test fun mapOpenedAndClosedBalance() {
        val store = makeStore()
        assertEquals(0, store.mapsOpen.value)
        store.mapOpened()
        store.mapOpened()
        assertEquals(2, store.mapsOpen.value)
        store.mapClosed()
        store.mapClosed()
        assertEquals(0, store.mapsOpen.value)
        try {
            store.mapClosed()
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        assertEquals(0, store.mapsOpen.value)
    }

    @Test fun awaitMapsClosedReturnsAtOnceWithNoMaps() = runBlocking<Unit> {
        val store = makeStore()
        val t = System.nanoTime()
        store.awaitMapsClosed()
        assertTrue(ms(t) < 100)
    }

    @Test fun awaitMapsClosedWaitsForTheLastMap() = runBlocking<Unit> {
        val store = makeStore()
        store.mapOpened()
        store.mapOpened()
        val waiting = async(Dispatchers.Default) { store.awaitMapsClosed(5_000) }
        delay(150)
        assertFalse(waiting.isCompleted)
        store.mapClosed()
        delay(100)
        assertFalse("one map is still open", waiting.isCompleted)
        store.mapClosed()
        val t = System.nanoTime()
        withTimeout(1_000) { waiting.await() }
        assertTrue(ms(t) < 500)
    }

    @Test fun awaitMapsClosedGivesUpAfterTheTimeout() = runBlocking<Unit> {
        val store = makeStore()
        store.mapOpened()
        val t = System.nanoTime()
        store.awaitMapsClosed(150)
        val took = ms(t)
        assertTrue("took $took ms", took in 140..1_000)
        assertEquals(1, store.mapsOpen.value)
    }

    @Test fun aDownloadWritesNothingUntilTheMapCloses() = runBlocking<Unit> {
        val store = makeStore()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val fake = FakeHttp()
        fake.reset { url ->
            events += "request"
            if (url.path == "/planet") FakeHttp.Reply(200, """{"tiles":["$good"]}""".toByteArray())
            else FakeHttp.Reply(200, byteArrayOf(7))
        }
        store.mapOpened()
        val running = async(Dispatchers.Default) { MapDownload(store, fake::open).download(plan, "X") }
        delay(200)
        assertTrue("the gate is up before anything is written", store.writing.value)
        assertTrue("no area begun while a map is open", store.unfinishedAreas().isEmpty())
        assertTrue("no request while a map is open", events.isEmpty())
        events += "closed"
        store.mapClosed()
        val area = withTimeout(10_000) { running.await() }
        assertEquals("closed", events.first())
        assertEquals(listOf(area.id), store.areas().map { it.id })
        assertFalse(store.writing.value)
    }

    @Test fun aResumeWritesNothingUntilTheMapCloses() = runBlocking<Unit> {
        val store = makeStore()
        val id = store.beginArea(plan, "X")
        val fake = FakeHttp()
        fake.reset { url ->
            if (url.path == "/planet") FakeHttp.Reply(200, """{"tiles":["$good"]}""".toByteArray())
            else FakeHttp.Reply(200, byteArrayOf(7))
        }
        store.mapOpened()
        val running = async(Dispatchers.Default) { MapDownload(store, fake::open).resume(id, plan) }
        delay(200)
        assertTrue(fake.urls.isEmpty())
        assertFalse(store.hasTile(plan.tiles[0], id))
        store.mapClosed()
        withTimeout(10_000) { running.await() }
        assertEquals(listOf(id), store.areas().map { it.id })
    }

    @Test fun removeAreaDeletesOnlyAfterTheMapsClose() = runBlocking<Unit> {
        val store = makeStore()
        val id = store.beginArea(plan, "A")
        store.finishArea(id)
        store.mapOpened()
        val removing = launch(Dispatchers.Default) { store.removeArea(id) }
        delay(150)
        assertTrue(store.writing.value)
        assertEquals("still there while a map is open", listOf(id), store.areas().map { it.id })
        store.mapClosed()
        withTimeout(5_000) { removing.join() }
        assertTrue(store.areas().isEmpty())
        assertFalse(store.writing.value)
    }

    @Test fun removeAreaWithNoMapsDeletesAtOnce() = runBlocking<Unit> {
        val store = makeStore()
        val id = store.beginArea(plan, "A")
        store.finishArea(id)
        store.removeArea(id)
        assertTrue(store.areas().isEmpty())
        assertFalse(store.writing.value)
    }

    /**
     * A Delete is the person's explicit action: leaving the screen while it
     * waits for the maps must not drop it. The work runs on the store's own
     * scope; the caller's scope only watches.
     */
    @Test fun aRemoveWhoseCallerGoesAwayStillDeletes() = runBlocking<Unit> {
        val store = makeStore()
        val id = store.beginArea(plan, "A")
        store.finishArea(id)
        store.mapOpened()
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        caller.launch { store.removeArea(id) }
        delay(100)
        assertTrue(store.writing.value)
        caller.cancel() // the Maps card left the screen
        delay(100)
        assertEquals("still waiting for the map", listOf(id), store.areas().map { it.id })
        store.mapClosed()
        withTimeout(5_000) { while (store.areas().isNotEmpty() || store.writing.value) delay(10) }
        assertTrue(store.areas().isEmpty())
        assertFalse("the gate comes down after the delete", store.writing.value)
    }

    @Test fun theGateIsReleasedWhenARemoveFails() = runBlocking<Unit> {
        val store = makeStore()
        val id = store.beginArea(plan, "A")
        store.finishArea(id)
        // Break the delete: its first statement now names a table that is gone.
        val raw = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(store.file.path)
        raw.prepare("DROP TABLE area_tiles").use { it.step() }
        raw.close()
        try {
            store.removeArea(id)
            fail("expected the delete to fail")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        assertFalse(store.writing.value)
    }

    // --- A map may not open once a writer has begun (the gate and the count share one lock).

    @Test fun aMapOpensWhenNothingIsWriting() {
        val store = makeStore()
        assertTrue(store.mapOpened())
        assertEquals(1, store.mapsOpen.value)
        store.mapClosed()
    }

    @Test fun aMapIsRefusedWhileAWriterHoldsTheGate() {
        val store = makeStore()
        store.beginWriting()
        assertFalse(store.mapOpened())
        assertEquals("a refused open is not counted", 0, store.mapsOpen.value)
        store.endWriting()
        try {
            store.mapClosed()
            fail("a refused open needs no matching close")
        } catch (_: IllegalStateException) {
        }
        assertTrue("open again once the gate is down", store.mapOpened())
        store.mapClosed()
    }

    @Test fun aLateMapAfterTheWritersCheckIsRefused() = runBlocking<Unit> {
        val store = makeStore()
        store.beginWriting()
        store.awaitMapsClosed() // the writer's check passes: no maps
        // A RouteMap that still saw `writing == false` now tries to open.
        assertFalse(store.mapOpened())
        assertEquals(0, store.mapsOpen.value)
        store.endWriting()
    }

    @Test fun noMapOpensDuringADownload() = runBlocking<Unit> {
        val store = makeStore()
        val opens = Collections.synchronizedList(mutableListOf<Boolean>())
        val fake = FakeHttp()
        fake.reset { url ->
            opens += store.mapOpened() // a map trying to open mid-write
            if (url.path == "/planet") FakeHttp.Reply(200, """{"tiles":["$good"]}""".toByteArray())
            else FakeHttp.Reply(200, byteArrayOf(7))
        }
        MapDownload(store, fake::open).download(plan, "X")
        assertTrue(opens.isNotEmpty())
        assertTrue("every open during the download is refused", opens.none { it })
        assertEquals(0, store.mapsOpen.value)
    }

    @Test fun closingTheStoreStopsItsWork() {
        val store = makeStore()
        assertTrue(store.work.isActive)
        store.close()
        assertFalse(store.work.isActive)
    }
}
