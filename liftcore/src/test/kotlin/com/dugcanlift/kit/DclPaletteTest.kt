package com.dugcanlift.kit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Pins both sets to LiftCore.Theme's values, which ThemeTests pins on iOS. */
class DclPaletteTest {
    private fun hex(argb: Long) = String.format("%06X", argb and 0xFFFFFF)

    @Test fun `dark is the original palette`() {
        assertEquals(listOf("1C1B19", "242220", "EDE7DD", "A39C8E", "C1442C", "7C8B7A", "3A3733", "F7F1E8"),
            listOf(DclPalette.BG, DclPalette.SURFACE, DclPalette.TEXT, DclPalette.MUTED,
                   DclPalette.ACCENT, DclPalette.ACCENT2, DclPalette.RULE, DclPalette.ON_ACCENT).map(::hex))
    }

    @Test fun `light is the approved palette`() {
        assertEquals(listOf("F4EFE7", "FFFCF7", "26221E", "665E52", "B23C25", "56664F", "DCD3C5", "FFFAF3"),
            listOf(DclPalette.BG_LIGHT, DclPalette.SURFACE_LIGHT, DclPalette.TEXT_LIGHT, DclPalette.MUTED_LIGHT,
                   DclPalette.ACCENT_LIGHT, DclPalette.ACCENT2_LIGHT, DclPalette.RULE_LIGHT, DclPalette.ON_ACCENT_LIGHT).map(::hex))
    }

    // --- Drift against the canonical iOS palette -------------------------

    private val fixtureText = javaClass.getResourceAsStream("/fixtures/palette.json")!!.bufferedReader().readText()
    private val fixture = JSONObject(fixtureText)

    /** Every `const val` on DclPalette, by name. */
    private val constants: Map<String, Long> = DclPalette::class.java.declaredFields
        .filter { it.type == java.lang.Long.TYPE && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .associate { it.name to it.getLong(null) }

    private fun argb(hex: Any?): Long = if (hex == null || hex == JSONObject.NULL) 0L else 0xFF000000L or hex.toString().toLong(16)

    @Test fun `every canonical token exists with the canonical value`() {
        val tokens = fixture.getJSONObject("tokens")
        val expected = mutableMapOf<String, Long>()
        for (name in tokens.keys()) {
            val t = tokens.getJSONObject(name)
            val android = t.getString("android")
            expected[android] = argb(t.opt("dark"))
            expected["${android}_LIGHT"] = argb(t.get("light"))
        }
        // Both directions: a token missing here, and a constant here that the
        // canonical palette does not have, are both drift.
        assertEquals("DclPalette and LiftCore.Theme disagree on which tokens exist",
            expected.keys.sorted(), constants.keys.sorted())
        for ((name, value) in expected) {
            assertEquals(name, String.format("%08X", value), String.format("%08X", constants.getValue(name)))
        }
    }

    /** When the iOS kit is checked out beside this one, the fixture copies must be identical. */
    @Test fun `fixture matches the iOS kit's copy`() {
        val ios = File(System.getProperty("user.dir")).resolve("../../dugcanlift-kit/Tests/LiftCoreTests/Fixtures/palette.json")
        assumeTrue("dugcanlift-kit is not checked out beside this repo", ios.isFile)
        assertEquals("palette.json differs between the iOS and Android kits", ios.readText(), fixtureText)
    }

    private fun luminance(argb: Long): Double {
        fun lin(c: Long): Double { val x = (c and 0xFF) / 255.0; return if (x <= 0.04045) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(argb shr 16) + 0.7152 * lin(argb shr 8) + 0.0722 * lin(argb)
    }

    private fun contrast(a: Long, b: Long): Double {
        val (x, y) = luminance(a) to luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    /** The KDoc's AA claim, checked: dark ACCENT as text (3.12:1) is why ACCENT_TEXT exists. */
    @Test fun `every text pairing passes AA in both sets`() {
        val tokens = fixture.getJSONObject("tokens")
        val pairs = fixture.getJSONArray("textPairs")
        for (i in 0 until pairs.length()) {
            val (fg, bg) = pairs.getJSONArray(i).let { it.getString(0) to it.getString(1) }
            for (suffix in listOf("", "_LIGHT")) {
                val f = constants.getValue(tokens.getJSONObject(fg).getString("android") + suffix)
                val g = constants.getValue(tokens.getJSONObject(bg).getString("android") + suffix)
                val ratio = contrast(f, g)
                assertTrue("$fg on $bg$suffix is ${"%.2f".format(ratio)}:1", ratio >= 4.5)
            }
        }
    }
}
