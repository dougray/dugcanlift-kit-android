package com.dugcanlift.kit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a sender needs to know about one recorded run, walk or hike. `type` is the wire's: 0 run, 1 walk, 2 hike.
 * `endedAtEpochMs` null is still recording, and is never sent. `route` is (latitude, longitude) in recorded order.
 */
data class OutdoorShareActivity(val type: Int, val startedAtEpochMs: Long, val endedAtEpochMs: Long?, val distanceMeters: Double, val climbMeters: Double,
    val route: List<Pair<Double, Double>> = emptyList())

/**
 * The outdoor parts of a share link (SHARE-FORMAT "Outdoor"), ported from LIFT web's outdoor.js line for line.
 * Every sender must produce the same numbers and the same polyline string, so the rounding here is spelled out
 * rather than left to `kotlin.math.round`, which rounds halves to even where JavaScript's Math.round rounds up.
 */
object OutdoorShare {
    const val TYPE_COUNT = 3
    const val TRIM_METERS = 200.0
    const val MAX_SHARED_POINTS = 150
    /** Shorter than this and a pace is mostly GPS noise. */
    const val MINIMUM_PACE_DISTANCE_METERS = 1000.0
    const val EARTH_RADIUS_METERS = 6_371_000.0

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * Math.PI / 180; val p2 = lat2 * Math.PI / 180
        val dp = (lat2 - lat1) * Math.PI / 180; val dl = (lon2 - lon1) * Math.PI / 180
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** A day's `o`: its finished activities in start order. Pass only the activities that started that day. */
    fun day(activities: List<OutdoorShareActivity>): List<ShareOutdoor> =
        finished(activities).sortedBy { it.startedAtEpochMs }.map { a ->
            ShareOutdoor(a.type, jsRound(durationMs(a)!! / 1000.0), jsRound(a.distanceMeters), jsRound(a.climbMeters))
        }

    /** `ob`, all-time, in type order; null when nothing is finished. Blank stays blank: no distance ever measured is no farthest, not 0. */
    fun bests(activities: List<OutdoorShareActivity>): List<ShareOutdoorBest>? {
        val done = finished(activities)
        return (0 until TYPE_COUNT).mapNotNull { type ->
            val ofType = done.filter { it.type == type }
            if (ofType.isEmpty()) return@mapNotNull null
            val farthest = ofType.map { it.distanceMeters }.filter { it > 0 }.maxOrNull()
            val longest = ofType.mapNotNull { durationMs(it) }.filter { it > 0 }.maxOrNull()
            val fastest = ofType.filter { it.distanceMeters >= MINIMUM_PACE_DISTANCE_METERS }
                .mapNotNull { a -> durationMs(a)?.let { it / 1000.0 / a.distanceMeters } }.filter { it > 0 }.minOrNull()
            ShareOutdoorBest(type, ofType.size, farthest?.let { jsRound(it) }, longest?.let { jsRound(it / 1000.0) }, fastest?.let { jsRound(it * 1000) })
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * `lr`: the newest finished activity with two or more route points, or null. Null too when fewer than two points
     * survive the trim -- a sender never falls back to an older route. Only call this when the person opted in.
     */
    fun lastRoute(activities: List<OutdoorShareActivity>): ShareLastRoute? {
        // maxBy keeps the first of equal starts, as the web's stable descending sort does.
        val last = finished(activities).filter { it.route.size > 1 }.maxByOrNull { it.startedAtEpochMs } ?: return null
        val kept = trimAndThin(last.route)
        if (kept.size < 2) return null
        return ShareLastRoute(last.type, Math.floorDiv(last.startedAtEpochMs, 1000L), jsRound(durationMs(last)!! / 1000.0),
            jsRound(last.distanceMeters), jsRound(last.climbMeters), encodePolyline(kept))
    }

    /** The route less every point within 200 m (along the route) of either end -- usually someone's front door -- then thinned to 150. */
    fun trimAndThin(route: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val n = route.size
        if (n < 2) return emptyList()
        val along = DoubleArray(n)
        for (i in 1 until n) along[i] = along[i - 1] + haversineMeters(route[i - 1].first, route[i - 1].second, route[i].first, route[i].second)
        val total = along[n - 1]
        val kept = route.filterIndexed { i, _ -> along[i] >= TRIM_METERS && total - along[i] >= TRIM_METERS }
        if (kept.size <= MAX_SHARED_POINTS) return kept
        return List(MAX_SHARED_POINTS) { j -> kept[floor(j * (kept.size - 1).toDouble() / (MAX_SHARED_POINTS - 1) + 0.5).toInt()] }
    }

    /** Google's encoded polyline at precision 5. Each coordinate is floor(v * 100000 + 0.5): the platforms' own rounds disagree on negative halves. */
    fun encodePolyline(points: List<Pair<Double, Double>>): String {
        val out = StringBuilder()
        fun chunk(value: Int) {
            var v = if (value < 0) (value shl 1).inv() else value shl 1
            while (v >= 0x20) { out.append(((0x20 or (v and 0x1f)) + 63).toChar()); v = v shr 5 }
            out.append((v + 63).toChar())
        }
        var prevLat = 0; var prevLon = 0
        points.forEach { (latitude, longitude) ->
            val lat = floor(latitude * 100000 + 0.5).toInt(); val lon = floor(longitude * 100000 + 0.5).toInt()
            chunk(lat - prevLat); chunk(lon - prevLon)
            prevLat = lat; prevLon = lon
        }
        return out.toString()
    }

    /** Back to (latitude, longitude). A truncated string yields the points before the break, never a throw. */
    fun decodePolyline(text: String): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        var index = 0; var lat = 0; var lon = 0
        fun next(): Int? {
            var result = 0; var shift = 0; var b: Int
            do {
                if (index >= text.length) return null
                b = text[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            return if ((result and 1) != 0) (result shr 1).inv() else result shr 1
        }
        while (index < text.length) {
            val dLat = next() ?: break; val dLon = next() ?: break
            lat += dLat; lon += dLon
            points.add(lat / 100000.0 to lon / 100000.0)
        }
        return points
    }

    private fun finished(activities: List<OutdoorShareActivity>) = activities.filter { it.endedAtEpochMs != null && it.type in 0 until TYPE_COUNT }
    private fun durationMs(a: OutdoorShareActivity): Long? = a.endedAtEpochMs?.let { it - a.startedAtEpochMs }
    /** JavaScript's Math.round -- halves toward +infinity, which Java's Math.round also does. A non-finite measurement is sent as 0, as `|| 0` does for a missing one. */
    private fun jsRound(v: Double): Long = if (v.isFinite()) Math.round(v) else 0
}
