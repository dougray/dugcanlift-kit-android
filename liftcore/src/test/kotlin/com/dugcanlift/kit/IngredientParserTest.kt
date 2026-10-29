package com.dugcanlift.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors `IngredientParserTests` in the iOS build. The two parsers must agree:
 * a recipe written on one phone can reach the other through the shared wire
 * format, and a quantity that parses differently on each side is a bug nobody
 * would think to look for.
 */
class IngredientParserTest {

    @Test
    fun `parses quantity unit and item`() {
        val parsed = IngredientParser.parse("2 tbsp olive oil")
        assertEquals(2.0, parsed.qty!!, 0.0001)
        assertEquals("tbsp", parsed.unit)
        assertEquals("olive oil", parsed.item)
    }

    @Test
    fun `grams are resolved for macro lookup`() {
        assertEquals(400.0, IngredientParser.parse("400 g chicken thigh").grams!!, 0.0001)
        assertNull(
            "only grams resolve to a mass",
            IngredientParser.parse("2 tbsp olive oil").grams
        )
    }

    @Test
    fun `counts get the sentinel not a unit`() {
        val parsed = IngredientParser.parse("2 eggs")
        assertEquals(2.0, parsed.qty!!, 0.0001)
        assertEquals("eggs", parsed.item)
        assertEquals(IngredientParser.COUNT_UNIT, parsed.unit)
    }

    /**
     * The sentinel must be something nobody can type, or a real ingredient
     * measured in it would silently be treated as a count.
     */
    @Test
    fun `sentinel cannot collide with typed text`() {
        assertTrue(IngredientParser.COUNT_UNIT.contains('\u0000'))
    }

    @Test
    fun `fractions`() {
        assertEquals(0.5, IngredientParser.parse("1/2 cup rice").qty!!, 0.0001)
        assertEquals(1.5, IngredientParser.parse("1 1/2 cups rice").qty!!, 0.0001)
        assertEquals(0.5, IngredientParser.parse("0.5 kg beef").qty!!, 0.0001)
    }

    @Test
    fun `unparseable lines keep their raw text and nothing else`() {
        val parsed = IngredientParser.parse("salt to taste")
        assertEquals("salt to taste", parsed.rawText)
        assertNull("a line that did not parse must not invent an item", parsed.item)
        assertNull(parsed.qty)
    }

    @Test
    fun `raw text is kept even when the parse succeeds`() {
        assertEquals("2 tbsp olive oil", IngredientParser.parse("2 tbsp olive oil").rawText)
    }

    // Spelled-out units

    private data class Case(val line: String, val qty: Double, val unit: String, val item: String)

    /**
     * The case table for spelled-out units. The same lines and answers are in
     * `IngredientParserTests` (dugcanlift-kit, iOS), `coach/parser.test.mjs`
     * (dugcanlift-coach) and `lift/ingredient-parser.test.mjs`
     * (dugcanlift-site): all four parsers must give the same answer.
     */
    private val spelledCases = listOf(
        Case("1.5 pounds ground beef", 1.5, "lb", "ground beef"),
        Case("2 Tablespoons olive oil", 2.0, "tbsp", "olive oil"),
        Case("500 grams flour", 500.0, "g", "flour"),
        Case("1 kilo potatoes", 1.0, "kg", "potatoes"),
        Case("8 ounces cream cheese, softened", 8.0, "oz", "cream cheese, softened"),
        Case("1 litre stock", 1.0, "l", "stock"),
        Case("250 millilitres milk", 250.0, "ml", "milk"),
        Case("3 Teaspoons. sea salt", 3.0, "tsp", "sea salt"),
        // The known cost of the rule, pinned on purpose: "pound cake" is read
        // as a pound of cake, exactly as "1 lb cake mix" already is.
        Case("1 pound cake mix", 1.0, "lb", "cake mix"),
        Case("1 lb cake mix", 1.0, "lb", "cake mix"),
        // Lines that parsed before are unchanged.
        Case("2 eggs", 2.0, IngredientParser.COUNT_UNIT, "eggs"),
        Case("2 lbs chicken", 2.0, "lbs", "chicken"),
        Case("1 1/2 cups flour", 1.5, "cups", "flour"),
    )

    @Test
    fun `spelled-out units are stored as their abbreviation`() {
        for (c in spelledCases) {
            val parsed = IngredientParser.parse(c.line)
            assertEquals(c.line, c.qty, parsed.qty!!, 0.000001)
            assertEquals(c.line, c.unit, parsed.unit)
            assertEquals(c.line, c.item, parsed.item)
            assertEquals(c.line, c.line, parsed.rawText)
        }
    }

    /**
     * This kit has no per-unit costing (`gramsPerUnit` lives in the iOS kit and
     * in both web builds' `foods.js`); its `grams` field is set for `g` only,
     * so that is what a spelled-out line is held to here.
     */
    @Test
    fun `spelled-out grams fill the grams field and nothing else does`() {
        assertEquals(500.0, IngredientParser.parse("500 grams flour").grams!!, 0.0001)
        assertNull(IngredientParser.parse("1.5 pounds ground beef").grams)
        assertNull(IngredientParser.parse("2 Tablespoons olive oil").grams)
    }

    @Test
    fun `every spelled-out word maps to its abbreviation in any case`() {
        val table = listOf(
            "gram" to "g", "grams" to "g", "gramme" to "g", "grammes" to "g",
            "kilogram" to "kg", "kilograms" to "kg", "kilogramme" to "kg", "kilogrammes" to "kg",
            "kilo" to "kg", "kilos" to "kg",
            "milligram" to "mg", "milligrams" to "mg", "milligramme" to "mg", "milligrammes" to "mg",
            "ounce" to "oz", "ounces" to "oz",
            "pound" to "lb", "pounds" to "lb",
            "teaspoon" to "tsp", "teaspoons" to "tsp",
            "tablespoon" to "tbsp", "tablespoons" to "tbsp",
            "milliliter" to "ml", "milliliters" to "ml", "millilitre" to "ml", "millilitres" to "ml",
            "liter" to "l", "liters" to "l", "litre" to "l", "litres" to "l",
        )
        for ((word, abbreviation) in table) {
            for (spelling in listOf(word, word.uppercase(), word.replaceFirstChar { it.uppercase() })) {
                val line = "2 $spelling, rice"
                val parsed = IngredientParser.parse(line)
                assertEquals(line, abbreviation, parsed.unit)
                assertEquals(line, "rice", parsed.item)
            }
        }
    }

    /** "3 Teaspoons." alone names no ingredient, exactly as "3 tsp." does. */
    @Test
    fun `a unit with nothing after it is still not an ingredient`() {
        for (line in listOf("3 Teaspoons.", "3 tsp.")) {
            val parsed = IngredientParser.parse(line)
            assertNull(line, parsed.qty)
            assertNull(line, parsed.unit)
            assertNull(line, parsed.item)
        }
    }

    @Test
    fun `out-of-scope units are still counts`() {
        for (line in listOf("2 t sugar", "2 T butter", "1 c. milk", "1 pt cream", "1 qt stock", "1 stick butter", "2 fl oz rum")) {
            assertEquals(line, IngredientParser.COUNT_UNIT, IngredientParser.parse(line).unit)
        }
    }

    /**
     * The web parsers keep the table in a plain object, whose prototype's
     * names must not read as units. Pinned here too so all four agree.
     */
    @Test
    fun `a word that only looks like a table key is not a unit`() {
        for (line in listOf("2 constructor bolts", "1 toString cake", "3 __proto__ things")) {
            assertEquals(line, IngredientParser.COUNT_UNIT, IngredientParser.parse(line).unit)
        }
    }
}
