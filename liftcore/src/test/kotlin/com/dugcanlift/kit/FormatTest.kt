package com.dugcanlift.kit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The same cases and strings as `testTrimmedMatchesAndroidTrimZeros` in
 * dugcanlift-kit's IngredientParserTests, so both platforms print a quantity
 * identically.
 */
class FormatTest {
    @Test fun `matches iOS CookFormat trimmed`() {
        val cases = listOf(
            4.0 to "4", 2.5 to "2.5", 1.0 / 3 to "0.333333", 1.5 to "1.5",
            0.1 + 0.2 to "0.3", 1e20 to "1e+20", -2.0 to "-2", 1234567.5 to "1.23457e+06",
        )
        for ((value, expected) in cases) assertEquals("$value", expected, value.trimZeros())
    }

    @Test fun `huge values are not clamped to Int MAX_VALUE`() {
        assertEquals("1e+20", 1e20.trimZeros())
        assertEquals("3000000000", 3e9.trimZeros())
    }

    @Test fun `small fractions stay readable`() {
        assertEquals("0.0001", 0.0001.trimZeros())
        assertEquals("1e-05", 0.00001.trimZeros())
    }
}
