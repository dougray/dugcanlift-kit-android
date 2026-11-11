package com.dugcanlift.liftmap

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ported from the Swift kit's `MapStyleTests` and `RouteMapDecisionTests`. */
class MapStyleTest {
    /** The module's own assets, read off disk: unit tests have no Context. */
    private fun template(dark: Boolean) = File("src/main/assets/map/${MapStyle.assetName(dark)}").readText()

    @Test fun styleURLPercentEncodesTheStorePath() {
        assertEquals(
            "mbtiles:///tmp/Application%20Support/LiftMap/streets.mbtiles",
            MapStyle.mbtilesUrl(File("/tmp/Application Support/LiftMap/streets.mbtiles")),
        )
    }

    @Test fun filledStyleNamesNoServer() {
        val store = File("/tmp/Application Support/x.mbtiles")
        for (dark in listOf(false, true)) {
            val text = MapStyle.fill(template(dark), store)
            assertFalse(text.contains("http"))
            assertFalse(text.contains("{{"))
            assertTrue(text.contains("mbtiles:///tmp/Application%20Support/x.mbtiles"))
            assertTrue(text.contains("asset://map/fonts/{fontstack}/{range}.pbf"))
            JSONObject(text) // parses
        }
    }

    @Test fun glyphsURLKeepsItsTokens() {
        assertTrue(MapStyle.GLYPHS.startsWith("asset://"))
        assertTrue(MapStyle.GLYPHS.endsWith("/fonts/{fontstack}/{range}.pbf"))
    }

    @Test fun theStylesNameTheTwoTokensTheyAreFilledWith() {
        for (dark in listOf(false, true)) {
            val t = template(dark)
            assertTrue(t.contains("{{TILES}}"))
            assertTrue(t.contains("{{GLYPHS}}"))
        }
    }
}

class RouteMapDecisionTest {
    private val a = 39.74 to -104.99
    private val b = 39.75 to -104.98

    @Test fun onePointIsALine() {
        assertEquals(RouteMapDecision.LINE, RouteMapDecision.decide(listOf(a)) { true })
        assertEquals(RouteMapDecision.LINE, RouteMapDecision.decide(emptyList()) { true })
    }

    @Test fun coveredIsAMap() {
        assertEquals(RouteMapDecision.MAP, RouteMapDecision.decide(listOf(a, b)) { true })
    }

    @Test fun notCoveredIsALine() {
        assertEquals(RouteMapDecision.LINE, RouteMapDecision.decide(listOf(a, b)) { false })
    }

    @Test fun theBoundsAskedAboutAreTheRoutes() {
        var asked: MapBounds? = null
        RouteMapDecision.decide(listOf(a, b)) { asked = it; true }
        assertEquals(MapBounds(south = 39.74, west = -104.99, north = 39.75, east = -104.98), asked)
    }
}

class StyleReloadTest {
    @Test fun sameStyleAndSameRevisionIsNoReload() {
        assertEquals(false, StyleReload.needed("{a}", "{a}", 3, 3))
    }

    @Test fun aNewRevisionReloadsTheSameStyle() {
        // A download finished or an area was deleted: MapLibre's tiles in memory are stale.
        assertEquals(true, StyleReload.needed("{a}", "{a}", 4, 3))
    }

    @Test fun aNewStyleReloads() {
        assertEquals(true, StyleReload.needed("{b}", "{a}", 3, 3))
    }

    @Test fun theFirstStyleLoads() {
        assertEquals(true, StyleReload.needed("{a}", null, 0, null))
    }
}

class RouteFitPaddingTest {
    @Test fun theBottomClearsTheAttribution() {
        for (density in listOf(1f, 2f, 2.625f, 3.5f)) {
            val p = RouteFitPadding.forDensity(density)
            assertTrue("density $density: $p", p.bottom > p.left && p.bottom > p.top && p.bottom > p.right)
        }
    }

    @Test fun theSidesAre24AndTheBottom50() {
        assertEquals(RouteFitPadding(24, 24, 24, 50), RouteFitPadding.forDensity(1f))
        assertEquals(RouteFitPadding(72, 72, 72, 150), RouteFitPadding.forDensity(3f))
        assertEquals(RouteFitPadding(63, 63, 63, 131), RouteFitPadding.forDensity(2.625f))
    }

    @Test fun aViewTooSmallForThePaddingIsNotFitted() {
        val p = RouteFitPadding.forDensity(1f)
        assertEquals(false, p.fits(48, 200))
        assertEquals(false, p.fits(200, 74))
        assertEquals(true, p.fits(49, 75))
    }
}
