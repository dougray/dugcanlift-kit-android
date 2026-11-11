package com.dugcanlift.liftmap

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * What a street-map download covers: a wide, coarse layer (zoom 0-10) and a
 * detailed one (zoom 11-14), and the tiles and size that come to.
 */
@ConsistentCopyVisibility
data class StreetAreaPlan private constructor(
    val latitude: Double,
    val longitude: Double,
    /** The detail radius chosen; 0 for a route plan, which is not a radius. */
    val detailKm: Int,
    val wide: MapBounds,
    val detail: MapBounds,
) {
    constructor(latitude: Double, longitude: Double, detailKm: Int) : this(
        latitude, longitude, detailKm,
        StreetTiles.bounds(latitude, longitude, WIDE_KM.toDouble()),
        StreetTiles.bounds(latitude, longitude, detailKm.toDouble()),
    )

    val tiles: List<TileID> = WIDE_ZOOMS.flatMap { StreetTiles.tiles(wide, it) } +
        DETAIL_ZOOMS.flatMap { StreetTiles.tiles(detail, it) }

    val estimatedBytes: Int = tiles.sumOf { StreetTiles.typicalBytes(it.z) }

    companion object {
        const val WIDE_KM = 100
        val WIDE_ZOOMS = 0..10
        val DETAIL_ZOOMS = 11..14
        val DETAIL_CHOICES = listOf(10, 25, 50)

        /**
         * Coach: the route's bounds grown 2 km on every side, with the wide
         * layer 100 km around its centre.
         */
        fun forRoute(bounds: MapBounds): StreetAreaPlan {
            val centerLat = (bounds.south + bounds.north) / 2
            val centerLon = (bounds.west + bounds.east) / 2
            val dLat = 2 / 111.32
            val dLon = 2 / (111.32 * max(0.01, cos(centerLat * PI / 180)))
            val detail = MapBounds(
                south = max(-85.0511, bounds.south - dLat), west = max(-180.0, bounds.west - dLon),
                north = min(85.0511, bounds.north + dLat), east = min(180.0, bounds.east + dLon),
            )
            val wide = StreetTiles.bounds(centerLat, centerLon, WIDE_KM.toDouble())
            return StreetAreaPlan(centerLat, centerLon, 0, wide, detail)
        }
    }
}
