package com.dugcanlift.kit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pinned to fixtures LIFT web's outdoor.js wrote. Never regenerate them from this code: agreeing with itself proves nothing. */
class OutdoorShareTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()
    private val expected = JSONObject(resource("outdoor-share-expected.json"))
    private val types = listOf("RUN", "WALK", "HIKE")

    /** The backup-format activities, as a sender holds them. */
    private val input: List<OutdoorShareActivity> = JSONObject(resource("outdoor-share-input.json")).getJSONArray("outdoor").let { a ->
        List(a.length()) { i ->
            val o = a.getJSONObject(i); val points = o.getJSONArray("routePoints")
            OutdoorShareActivity(types.indexOf(o.getString("activityType")), o.getLong("startedAtEpochMs"),
                if (o.isNull("endedAtEpochMs")) null else o.getLong("endedAtEpochMs"), o.getDouble("distanceMeters"), o.getDouble("elevationGainMeters"),
                List(points.length()) { j -> points.getJSONObject(j).let { it.getDouble("latitude") to it.getDouble("longitude") } })
        }
    }

    private fun tuple(o: ShareOutdoor) = listOf(o.type.toLong(), o.durationSec, o.distanceMeters, o.climbMeters)
    private fun tuple(b: ShareOutdoorBest) = listOf(b.type.toLong(), b.count.toLong(), b.farthestMeters, b.longestSec, b.fastestSecPerKm)
    private fun tuple(r: ShareLastRoute) = listOf<Any>(r.type.toLong(), r.startedAtEpochSec, r.durationSec, r.distanceMeters, r.climbMeters, r.polyline)
    /** A fixture array of tuples as Kotlin values, JSON null as null. */
    private fun rows(a: JSONArray) = List(a.length()) { i -> row(a.getJSONArray(i)) }
    private fun row(t: JSONArray) = List(t.length()) { j -> t.opt(j).let { v -> if (v == JSONObject.NULL) null else if (v is Number) v.toLong() else v } }

    @Test fun `day tuples match LIFT web and skip the unfinished hike`() {
        assertEquals(rows(expected.getJSONArray("o")), OutdoorShare.day(input).map(::tuple))
    }
    @Test fun `bests match LIFT web, including a null pace under 1 km`() {
        assertEquals(rows(expected.getJSONArray("ob")), OutdoorShare.bests(input)!!.map(::tuple))
    }
    @Test fun `last route matches LIFT web string for string`() {
        assertEquals(row(expected.getJSONArray("lr")), tuple(OutdoorShare.lastRoute(input)!!))
    }
    @Test fun `trimming and thinning keeps the count LIFT web kept`() {
        val loop = input.maxByOrNull { if (it.endedAtEpochMs != null) it.startedAtEpochMs else Long.MIN_VALUE }!!
        assertEquals(expected.getInt("trimmedPointCount"), OutdoorShare.trimAndThin(loop.route).size)
    }
    @Test fun `nothing finished sends no bests and no route`() {
        val unfinished = input.filter { it.endedAtEpochMs == null }
        assertNull(OutdoorShare.bests(unfinished)); assertNull(OutdoorShare.lastRoute(unfinished)); assertTrue(OutdoorShare.day(unfinished).isEmpty())
    }
    @Test fun `a route that does not survive the trim sends no lr rather than an older route`() {
        val walk = input.first { it.type == 1 }                         // 300 m: nothing is 200 m from both ends
        val older = input.first { it.type == 0 }.copy(startedAtEpochMs = walk.startedAtEpochMs - 1, endedAtEpochMs = walk.startedAtEpochMs)
        assertNull(OutdoorShare.lastRoute(listOf(older, walk)))
        assertNotNull(OutdoorShare.lastRoute(listOf(older)))
    }
    @Test fun `Google's worked example encodes and decodes`() {
        val points = listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", OutdoorShare.encodePolyline(points))
        assertEquals(points, OutdoorShare.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@"))
    }
    @Test fun `a negative half rounds up as the format spells out`() {
        assertEquals("??", OutdoorShare.encodePolyline(listOf(0.0 to -0.000005)))
    }
    @Test fun `a truncated polyline decodes the points before the break`() {
        assertEquals(listOf(38.5 to -120.2), OutdoorShare.decodePolyline("_p~iF~ps|U_ulL"))
    }
    @Test fun `the fixture route decodes back to the trimmed points`() {
        val loop = input.maxByOrNull { if (it.endedAtEpochMs != null) it.startedAtEpochMs else Long.MIN_VALUE }!!
        val decoded = OutdoorShare.decodePolyline(expected.getJSONArray("lr").getString(5))
        val kept = OutdoorShare.trimAndThin(loop.route)
        assertEquals(kept.size, decoded.size)
        kept.zip(decoded).forEach { (a, b) -> assertEquals(a.first, b.first, 0.000006); assertEquals(a.second, b.second, 0.000006) }
    }

    // The link.

    @Test fun `the link LIFT web wrote decodes with its outdoor parts`() {
        val fragment = resource("outdoor-share-link.txt").trim().substringAfter('#')
        val p = (ShareLinkCodec.decode(fragment) as ShareDecodeResult.Success).payload
        assertEquals(listOf(0, 2, 3), p.days.map { it.dayOffset })
        assertEquals(rows(expected.getJSONArray("o")), p.days.flatMap { it.outdoor!! }.map(::tuple))
        assertEquals(rows(expected.getJSONArray("ob")), p.outdoorBests!!.map(::tuple))
        assertEquals(row(expected.getJSONArray("lr")), tuple(p.lastRoute!!))
        assertEquals(p, (ShareLinkCodec.decode(ShareLinkCodec.encodeFragment(p)) as ShareDecodeResult.Success).payload)
    }
    @Test fun `a day with only an outdoor activity is still a day, and outdoor keys are written`() {
        val p = SharePayload(ShareClient("x", "N"), null, "2026-09-10", "2026-09-16", 1, listOf(ShareDay(3, null, null, null, null, emptyList(), null, null, OutdoorShare.day(input))),
            OutdoorShare.bests(input), OutdoorShare.lastRoute(input))
        val j = ShareLinkCodec.buildJson(p)
        assertEquals(3, j.getJSONArray("d").getJSONObject(0).getInt("k"))
        assertEquals("[[0,3000,10001,0],[1,240,300,0],[0,1720,2795,37]]", j.getJSONArray("d").getJSONObject(0).getJSONArray("o").toString())
        assertEquals("[[0,2,10001,3000,300],[1,1,300,240,null]]", j.getJSONArray("ob").toString())
        assertEquals(p, (ShareLinkCodec.decode(ShareLinkCodec.encodeFragment(p)) as ShareDecodeResult.Success).payload)
    }
    @Test fun `a payload without outdoor keys round-trips unchanged and writes none`() {
        val text = """{"v":1,"c":{"i":"x","n":"N","u":"lb"},"r":"2026-09-01","t":"2026-09-02","z":1,"x":[],"d":[{"k":0,"st":5000}]}"""
        val p = (ShareLinkCodec.decode("1u" + CompactEncoding.base64Url(text.toByteArray())) as ShareDecodeResult.Success).payload
        assertNull(p.days[0].outdoor); assertNull(p.outdoorBests); assertNull(p.lastRoute)
        val j = ShareLinkCodec.buildJson(p)
        assertFalse(j.has("ob")); assertFalse(j.has("lr")); assertFalse(j.getJSONArray("d").getJSONObject(0).has("o"))
        assertEquals(p, (ShareLinkCodec.decode(ShareLinkCodec.encodeFragment(p)) as ShareDecodeResult.Success).payload)
    }
    @Test fun `malformed outdoor parts are dropped without failing the payload`() {
        val text = """{"v":1,"c":{"i":"x","n":"N"},"r":"2026-09-01","t":"2026-09-02","z":1,"d":[{"k":0,"st":5000,"o":[[0,"x",1,1],[1,60,100,0],[2,60]]}],
            "ob":[[0,"two",1,1,1],[1,1,null,null,null]],"lr":[0,1789259200,1720,"2795",37,"_p~iF"]}"""
        val p = (ShareLinkCodec.decode("1u" + CompactEncoding.base64Url(text.toByteArray())) as ShareDecodeResult.Success).payload
        assertEquals(5000L, p.days[0].steps)
        assertEquals(listOf(ShareOutdoor(1, 60, 100, 0)), p.days[0].outdoor)
        assertEquals(listOf(ShareOutdoorBest(1, 1, null, null, null)), p.outdoorBests)
        assertNull(p.lastRoute)
        for (lr in listOf("\"route\"", "[0,1,2,3,4]", "{}", "[0,1,2,3,4,5]")) {
            val t = """{"v":1,"c":{"i":"x","n":"N"},"r":"2026-09-01","z":1,"d":[],"lr":$lr}"""
            assertNull((ShareLinkCodec.decode("1u" + CompactEncoding.base64Url(t.toByteArray())) as ShareDecodeResult.Success).payload.lastRoute)
        }
    }
}
