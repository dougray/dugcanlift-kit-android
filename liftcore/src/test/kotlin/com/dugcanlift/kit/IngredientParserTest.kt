package com.dugcanlift.kit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

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

    // Whitespace

    /**
     * Every whitespace character in the line becomes a plain space before
     * parsing: U+0009-U+000D, U+0020, U+00A0, U+1680, U+2000-U+200A, U+2028,
     * U+2029, U+202F, U+205F, U+3000, U+FEFF. The same cases are in the other
     * three parsers' suites.
     */
    private val whitespaceCases = listOf(
        Case("1.5\u00A0pounds ground beef", 1.5, "lb", "ground beef"), // NBSP between number and unit
        Case("2 cups\u00A0rice", 2.0, "cups", "rice"), // NBSP between unit and item
        Case("1 lb\u00A0ground\u00A0beef", 1.0, "lb", "ground beef"), // the item comes out with plain spaces
        Case("2\tcups rice", 2.0, "cups", "rice"), // tab between number and unit
        Case("\t2 cups rice", 2.0, "cups", "rice"), // a leading tab
        Case("250\u202Fg flour", 250.0, "g", "flour"), // U+202F narrow no-break space
        Case("1\u00A01/2 cups flour", 1.5, "cups", "flour"), // "1 1/2" with an NBSP
        Case("\uFEFF400 g chicken thigh", 400.0, "g", "chicken thigh"), // a byte-order mark
    )

    private val whitespaceSet = listOf(
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF,
    )

    @Test
    fun `whitespace of every kind splits words like a space`() {
        for (c in whitespaceCases) {
            val parsed = IngredientParser.parse(c.line)
            assertEquals(c.line, c.qty, parsed.qty!!, 0.0)
            assertEquals(c.line, c.unit, parsed.unit)
            assertEquals(c.line, c.item, parsed.item)
        }
    }

    @Test
    fun `every character in the set is a space wherever it falls`() {
        for (code in whitespaceSet) {
            val ws = code.toChar().toString()
            val hex = code.toString(16)
            val parsed = IngredientParser.parse("${ws}2${ws}1/2${ws}cups${ws}basmati${ws}rice${ws}")
            assertEquals(hex, 2.5, parsed.qty!!, 0.0)
            assertEquals(hex, "cups", parsed.unit)
            assertEquals(hex, "basmati rice", parsed.item)
        }
    }

    /**
     * U+200B zero-width space, U+001C file separator and U+0085 next line are
     * not in the set, so "cups" never becomes a unit through them.
     */
    @Test
    fun `a character outside the set is not a space`() {
        for (ws in listOf("\u200B", "\u001C", "\u0085")) {
            val parsed = IngredientParser.parse("2${ws}cups rice")
            val hex = ws[0].code.toString(16)
            assertEquals(hex, IngredientParser.COUNT_UNIT, parsed.unit)
            assertEquals(hex, "${ws}cups rice", parsed.item)
        }
    }

    @Test
    fun `rawText keeps the line byte for byte`() {
        val lines = whitespaceCases.map { it.line } + "\u3000salt\u00A0to taste\t"
        for (line in lines) {
            assertTrue(line, IngredientParser.parse(line).rawText.toByteArray(Charsets.UTF_8)
                .contentEquals(line.toByteArray(Charsets.UTF_8)))
        }
    }

    // --- The shared case table ------------------------------------------

    /**
     * dugcanlift-kit's `data/ingredient-lines.json`, copied byte for byte with
     * its `ingredient-lines.sha256`: the case table all four parsers are
     * checked against, written by hand from the rules in that repo's
     * `data/README.md`. Never edit either file here, and never regenerate them
     * from this parser.
     */
    private fun resourceBytes(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes()

    @Test
    fun `the case table is the kit's bytes`() {
        val sum = String(resourceBytes("ingredient-lines.sha256"), Charsets.UTF_8).trim()
        assertTrue("ingredient-lines.sha256 should be one bare sha256", sum.matches(Regex("[0-9a-f]{64}")))
        val actual = MessageDigest.getInstance("SHA-256").digest(resourceBytes("ingredient-lines.json"))
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "ingredient-lines.json does not match ingredient-lines.sha256. Copy dugcanlift-kit/data/" +
                "ingredient-lines.json AND ingredient-lines.sha256 over together; the kit writes the " +
                "checksum with node data/validate-ingredient-lines.mjs --write-checksum.",
            sum, actual
        )
    }

    /** When the iOS kit is checked out beside this one, the copies must be identical. */
    @Test
    fun `the case table matches the iOS kit's copy`() {
        val ios = File(System.getProperty("user.dir")).resolve("../../dugcanlift-kit/data/ingredient-lines.json")
        assumeTrue("dugcanlift-kit is not checked out beside this repo", ios.isFile)
        assertTrue("ingredient-lines.json differs between the two kits",
            ios.readBytes().contentEquals(resourceBytes("ingredient-lines.json")))
    }

    @Test
    fun `every line in the shared case table`() {
        val table = JSONObject(String(resourceBytes("ingredient-lines.json"), Charsets.UTF_8))
        assertEquals(IngredientParser.COUNT_UNIT, table.getString("countUnit"))
        val cases = table.getJSONArray("cases")
        assertTrue(cases.length() > 100)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val line = c.getString("line")
            val parsed = IngredientParser.parse(line)
            assertTrue(line, parsed.rawText.toByteArray(Charsets.UTF_8).contentEquals(line.toByteArray(Charsets.UTF_8)))
            if (c.optBoolean("unparsed")) {
                assertNull(line, parsed.qty)
                assertNull(line, parsed.unit)
                assertNull(line, parsed.item)
                assertNull(line, parsed.grams)
                continue
            }
            assertNotNull(line, parsed.qty)
            assertEquals(line, c.getDouble("qty"), parsed.qty!!, 1e-9)
            assertEquals(line, c.getString("unit"), parsed.unit)
            assertEquals(line, c.getString("item"), parsed.item)
            if (c.isNull("grams")) {
                assertNull(line, parsed.grams)
            } else {
                assertEquals(line, c.getDouble("grams"), parsed.grams!!, 1e-9)
            }
        }
    }
}
