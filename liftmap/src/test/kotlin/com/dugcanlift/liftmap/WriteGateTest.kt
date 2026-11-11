package com.dugcanlift.liftmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Writes pause maps. On Android the store's writer (androidx's bundled
 * SQLite) and MapLibre's reader (its own native SQLite) are two libraries in
 * one process, and POSIX locks do not exclude each other within a process, so
 * nothing in SQLite keeps them apart: the gate does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WriteGateTest {
    private val files = mutableListOf<File>()
    private val plan = StreetAreaPlan(39.7392, -104.9903, 10)

    @After fun tearDown() {
        files.forEach { f -> listOf("", "-journal").forEach { File(f.path + it).delete() } }
    }

    private fun makeStore(): StreetStore {
        val f = File(System.getProperty("java.io.tmpdir"), "gate-${UUID.randomUUID()}.mbtiles")
        files += f
        return StreetStore(f)
    }

    private val good = "https://tiles.openfreemap.org/planet/v1/{z}/{x}/{y}.pbf"

    @Test fun theGateNests() {
        val store = makeStore()
        assertFalse(store.writing.value)
        store.beginWriting()
        store.beginWriting()
        assertTrue(store.writing.value)
        store.endWriting()
        assertTrue("one writer is still writing", store.writing.value)
        store.endWriting()
        assertFalse(store.writing.value)
    }

    @Test fun endingAGateNobodyOpenedIsRefused() {
        val store = makeStore()
        try {
            store.endWriting()
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        assertFalse(store.writing.value)
    }

    @Test fun transitionsEmit() = runTest {
        val store = makeStore()
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.writing.toList(seen) }
        store.beginWriting()
        store.beginWriting()
        store.endWriting()
        store.endWriting()
        assertEquals(listOf(false, true, false), seen)
    }

    @Test fun theGateIsHeldDuringDeleteArea() = runTest {
        val store = makeStore()
        val id = store.beginArea(plan, "A")
        store.finishArea(id)
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.writing.toList(seen) }
        store.deleteArea(id)
        assertEquals(listOf(false, true, false), seen)
    }

    @Test fun theGateIsHeldForAWholeDownloadAndReleasedAfter() = runBlocking<Unit> {
        val store = makeStore()
        val fake = FakeHttp()
        val heldAtEveryRequest = Collections.synchronizedList(mutableListOf<Boolean>())
        fake.reset { url ->
            heldAtEveryRequest += store.writing.value
            if (url.path == "/planet") FakeHttp.Reply(200, """{"tiles":["$good"]}""".toByteArray())
            else FakeHttp.Reply(200, byteArrayOf(7))
        }
        MapDownload(store, fake::open).download(plan, "X")
        assertTrue(heldAtEveryRequest.isNotEmpty() && heldAtEveryRequest.all { it })
        assertFalse(store.writing.value)
    }

    @Test fun theGateIsReleasedWhenADownloadThrows() = runBlocking<Unit> {
        val store = makeStore()
        val fake = FakeHttp()
        fake.reset { url ->
            if (url.path == "/planet") FakeHttp.Reply(200, """{"tiles":["$good"]}""".toByteArray()) else FakeHttp.Reply(500)
        }
        try {
            MapDownload(store, fake::open).download(plan, "X")
            fail("expected Http(500)")
        } catch (_: MapDownloadException.Http) {
        }
        assertFalse(store.writing.value)

        fake.reset { FakeHttp.Reply(200, """{"tiles":["https://evil.example/{z}/{x}/{y}"]}""".toByteArray()) }
        val id = store.unfinishedAreas().first().id
        try {
            MapDownload(store, fake::open).resume(id, plan)
            fail("expected RefusedHost")
        } catch (_: MapDownloadException.RefusedHost) {
        }
        assertFalse(store.writing.value)
    }

    @Test fun theGateIsReleasedAfterACancel() = runBlocking<Unit> {
        val store = makeStore()
        val stalled = CountDownLatch(1)
        val http: (URL) -> HttpURLConnection = { url ->
            if (url.path == "/planet") {
                FakeHttp().also { f -> f.reset { FakeHttp.Reply(200, """{"tiles":["$good"]}""".toByteArray()) } }.open(url)
            } else {
                object : HttpURLConnection(url) {
                    private val gone = CountDownLatch(1)
                    override fun connect() {}
                    override fun usingProxy() = false
                    override fun disconnect() = gone.countDown()
                    override fun getResponseCode(): Int {
                        stalled.countDown()
                        gone.await(8, TimeUnit.SECONDS)
                        throw java.io.IOException("Socket closed")
                    }
                    override fun getInputStream(): InputStream = throw java.io.IOException("Socket closed")
                }
            }
        }
        val running = async(Dispatchers.Default) { MapDownload(store, http).download(plan, "X") }
        assertTrue(stalled.await(10, TimeUnit.SECONDS))
        assertTrue("held while the download runs", store.writing.value)
        running.cancelAndJoin()
        assertFalse(store.writing.value)
    }
}
