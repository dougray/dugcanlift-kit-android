package com.dugcanlift.kit

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
