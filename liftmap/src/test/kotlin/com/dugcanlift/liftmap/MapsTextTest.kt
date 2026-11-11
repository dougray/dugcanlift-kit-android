package com.dugcanlift.liftmap

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

class MapsTextTest {
    private val us = Locale.US

    @Test fun sizesReadAsTheIPhoneWritesThem() {
        assertEquals("Zero KB", MapsText.fileSize(0, us))
        assertEquals("999 bytes", MapsText.fileSize(999, us))
        assertEquals("25 KB", MapsText.fileSize(25_000, us))
        assertEquals("24.8 MB", MapsText.fileSize(24_800_000, us))
        assertEquals("40 MB", MapsText.fileSize(40_000_000, us))
        assertEquals("1.23 GB", MapsText.fileSize(1_234_000_000, us))
        assertEquals("About 40 MB", "About " + MapsText.fileSize(40_000_000, us))
    }

    @Test fun anAreaIsNamedByItsCentreAndNothingElse() {
        assertEquals("Streets around 39.74, -104.99", MapsText.nameAround(39.7392 to -104.9903))
    }

    @Test fun theDetailLineIsSizeAndDate() {
        val area = StreetArea(UUID.randomUUID(), "A", MapBounds(0.0, 0.0, 1.0, 1.0), 10,
            Instant.parse("2026-11-10T18:00:00Z"), 24_800_000)
        assertEquals("24.8 MB · Nov 10, 2026", MapsText.detailLine(area, ZoneId.of("UTC"), us))
    }

    @Test fun replanGivesBackTheSameArea() {
        val radius = StreetAreaPlan(39.7392, -104.9903, 25)
        val area = StreetArea(UUID.randomUUID(), "R", radius.detail, 25, Instant.now(), 0)
        assertEquals(radius.tiles, MapsText.replan(area).tiles)

        val route = StreetAreaPlan.forRoute(MapBounds(39.70, -105.00, 39.71, -104.99))
        val routeArea = StreetArea(UUID.randomUUID(), "Route", route.detail, 0, Instant.now(), 0)
        val again = MapsText.replan(routeArea)
        assertEquals(0, again.detailKm)
        assertEquals(route.detail.south, again.detail.south, 1e-9)
        assertEquals(route.detail.north, again.detail.north, 1e-9)
        assertEquals(route.detail.west, again.detail.west, 1e-9)
        assertEquals(route.detail.east, again.detail.east, 1e-9)
    }

    @Test fun theSheetSaysWhereAndWhatTheServerLearns() {
        assertEquals("OpenStreetMap data from OpenFreeMap", MapsText.SOURCE)
        assertEquals("OpenFreeMap learns which area you asked for, once.", MapsText.SERVER_LEARNS)
        assertEquals(10, MapsText.STARTING_KM)
    }
}
