package com.dugcanlift.liftmap

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** A latitude/longitude box, in degrees. */
data class MapBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    /** True when the boxes overlap; boxes that only touch at an edge do. */
    fun intersects(other: MapBounds): Boolean =
        south <= other.north && north >= other.south && west <= other.east && east >= other.west

    companion object {
        /**
         * The smallest box holding every point, each `(latitude, longitude)`;
         * null for no points. One point gives a zero-area box.
         */
        fun around(points: List<Pair<Double, Double>>): MapBounds? {
            val first = points.firstOrNull() ?: return null
            var s = first.first; var n = first.first
            var w = first.second; var e = first.second
            for (i in 1 until points.size) {
                val (lat, lon) = points[i]
                s = min(s, lat); n = max(n, lat)
                w = min(w, lon); e = max(e, lon)
            }
            return MapBounds(south = s, west = w, north = n, east = e)
        }
    }
}

/** One slippy-map tile. `y` counts from the top (XYZ); MBTiles stores TMS rows. */
data class TileID(val z: Int, val x: Int, val y: Int) {
    val tmsRow: Int get() = (1 shl z) - 1 - y
}

/** Slippy-map tile maths for sizing and listing a download. Pure. */
object StreetTiles {
    /** A square reaching [radiusKm] north, south, east and west of the centre. */
    fun bounds(latitude: Double, longitude: Double, radiusKm: Double): MapBounds {
        val dLat = radiusKm / 111.32
        val dLon = radiusKm / (111.32 * max(0.01, cos(latitude * PI / 180)))
        return MapBounds(
            south = max(-85.0511, latitude - dLat), west = max(-180.0, longitude - dLon),
            north = min(85.0511, latitude + dLat), east = min(180.0, longitude + dLon),
        )
    }

    fun x(longitude: Double, z: Int): Int {
        val n = (1 shl z).toDouble()
        return min(n.toInt() - 1, max(0, floor((longitude + 180) / 360 * n).toInt()))
    }

    fun y(latitude: Double, z: Int): Int {
        val n = (1 shl z).toDouble()
        val lat = max(-85.0511, min(85.0511, latitude)) * PI / 180
        return min(n.toInt() - 1, max(0, floor((1 - asinh(tan(lat)) / PI) / 2 * n).toInt()))
    }

    /** Number of tiles covering the bounds at one zoom. */
    fun count(b: MapBounds, z: Int): Int = (x(b.east, z) - x(b.west, z) + 1) * (y(b.south, z) - y(b.north, z) + 1)

    /** Every tile covering the bounds at one zoom. */
    fun tiles(b: MapBounds, z: Int): List<TileID> {
        val x0 = x(b.west, z); val x1 = x(b.east, z)
        val y0 = y(b.north, z); val y1 = y(b.south, z)
        if (x0 > x1 || y0 > y1) return emptyList()
        val out = ArrayList<TileID>((x1 - x0 + 1) * (y1 - y0 + 1))
        for (tx in x0..x1) for (ty in y0..y1) out += TileID(z, tx, ty)
        return out
    }

    /**
     * Typical stored OpenFreeMap tile size by zoom (decompressed bytes, as the
     * app keeps them), measured 2026-11-12 over real tiles and rounded to the
     * nearest 5,000: the mean of a dense city (Manhattan) and a mid-size one
     * (Denver), so the estimate leans slightly high for the places people run
     * rather than low. Rural figures sit far below and are kept as evidence in
     * the Swift kit's `map/tile-size-sample.json`. Identical in the Swift and JS
     * kits.
     */
    private val TYPICAL_BYTES = intArrayOf(
        85_000, 200_000, 980_000, 400_000, 320_000, // z0-4
        320_000, 200_000, 180_000, 150_000, 135_000, // z5-9
        70_000, 100_000, 115_000, 95_000, 155_000, // z10-14
    )

    fun typicalBytes(z: Int): Int = TYPICAL_BYTES[z.coerceIn(0, TYPICAL_BYTES.size - 1)]
}
