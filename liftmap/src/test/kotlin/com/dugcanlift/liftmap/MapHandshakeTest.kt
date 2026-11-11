package com.dugcanlift.liftmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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

    @Test fun theGateIsReleasedWhenTheWaitingWriterIsCancelled() = runBlocking<Unit> {
        val store = makeStore()
        val id = store.beginArea(plan, "A")
        store.finishArea(id)
        store.mapOpened()
        val removing = launch(Dispatchers.Default) { store.removeArea(id) }
        delay(100)
        assertTrue(store.writing.value)
        removing.cancelAndJoin()
        assertFalse(store.writing.value)
        assertEquals("a cancelled remove deletes nothing", listOf(id), store.areas().map { it.id })
        store.mapClosed()
    }
}
