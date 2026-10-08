package com.dugcanlift.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecipeIngredientDisplayTest {
    @Test fun `a count prints bare, never the sentinel`() {
        val eggs = IngredientParser.parse("2 eggs")
        assertEquals(IngredientParser.COUNT_UNIT, eggs.unit)
        assertEquals("2 eggs", eggs.displayText)
        assertFalse(eggs.displayText.contains("count"))
        assertFalse(eggs.displayText.contains('\u0000'))
    }

    @Test fun `a real unit still prints`() {
        assertEquals("1.5 cup oats", IngredientParser.parse("1 1/2 cup oats").displayText)
        assertEquals("0.333333 cup flour", IngredientParser.parse("1/3 cup flour").displayText)
    }

    @Test fun `an unparsed line shows as typed`() {
        assertEquals("a pinch of salt", IngredientParser.parse("a pinch of salt").displayText)
    }

    @Test fun `oversized quantities do not clamp or print Infinity`() {
        assertEquals("1e+20 g oats", IngredientParser.parse("99999999999999999999 g oats").displayText)
        val line = "9".repeat(400) + " g oats"
        assertEquals(line, IngredientParser.parse(line).displayText)
    }
}
