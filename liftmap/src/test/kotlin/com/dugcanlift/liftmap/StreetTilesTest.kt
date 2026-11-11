package com.dugcanlift.liftmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the Swift kit's `StreetTilesTests`, case for case, same numbers. */
class StreetTilesTest {
    @Test fun tileMaths() {
        assertEquals(3413, StreetTiles.x(-104.9903, 14))
        assertEquals(6218, StreetTiles.y(39.7392, 14))
        assertEquals(213, StreetTiles.x(-104.9903, 10))
        assertEquals(388, StreetTiles.y(39.7392, 10))
        val world = MapBounds(south = -85.0511, west = -180.0, north = 85.0511, east = 180.0)
        for (z in 0..4) assertEquals(1 shl (2 * z), StreetTiles.count(world, z))
        assertEquals(64, StreetTiles.tiles(world, 3).size)

        val b = StreetTiles.bounds(39.7392, -104.9903, 50.0)
        assertEquals(50 / 111.32, b.north - 39.7392, 1e-9)
        assertTrue(b.east + 104.9903 > b.north - 39.7392)
        assertEquals(64, StreetTiles.count(b, 11))
        assertEquals(2916, StreetTiles.count(b, 14))
        assertEquals(2916, StreetTiles.tiles(b, 14).size)

        val edge = StreetTiles.bounds(84.9, 179.9, 400.0)
        assertTrue(edge.north <= 85.0511)
        assertTrue(edge.east <= 180.0)
    }

    @Test fun tmsRow() {
        assertEquals(10165, TileID(14, 3413, 6218).tmsRow)
    }

    @Test fun planSizes() {
        val small = StreetAreaPlan(39.7392, -104.9903, 10)
        val big = StreetAreaPlan(39.7392, -104.9903, 50)
        assertTrue(small.estimatedBytes < big.estimatedBytes)
        assertEquals(small.tiles.size, small.tiles.toSet().size)
        assertEquals(big.tiles.size, big.tiles.toSet().size)
        assertEquals(big.tiles.sumOf { StreetTiles.typicalBytes(it.z) }, big.estimatedBytes)
        assertEquals(listOf(10, 25, 50), StreetAreaPlan.DETAIL_CHOICES)
        assertEquals(100, StreetAreaPlan.WIDE_KM)
    }

    @Test fun forRouteGrowsByTwoKm() {
        val route = MapBounds(south = 39.70, west = -105.00, north = 39.71, east = -104.99)
        val plan = StreetAreaPlan.forRoute(route)
        assertEquals(2 / 111.32, plan.detail.north - route.north, 1e-9)
        assertEquals(2 / 111.32, route.south - plan.detail.south, 1e-9)
        assertTrue(route.west - plan.detail.west > 2 / 111.32)
        assertEquals(0, plan.detailKm)
        assertEquals(plan.tiles.size, plan.tiles.toSet().size)
        assertTrue(plan.tiles.any { it.z == 14 })
    }

    @Test fun aroundAndIntersects() {
        assertNull(MapBounds.around(emptyList()))
        assertEquals(MapBounds(1.0, 2.0, 1.0, 2.0), MapBounds.around(listOf(1.0 to 2.0)))
        val b = MapBounds.around(listOf(1.0 to 2.0, 3.0 to -4.0))!!
        assertEquals(MapBounds(south = 1.0, west = -4.0, north = 3.0, east = 2.0), b)
        assertTrue(b.intersects(MapBounds(south = 3.0, west = 2.0, north = 5.0, east = 6.0)))
        assertFalse(b.intersects(MapBounds(south = 4.0, west = 0.0, north = 5.0, east = 1.0)))
    }
}
