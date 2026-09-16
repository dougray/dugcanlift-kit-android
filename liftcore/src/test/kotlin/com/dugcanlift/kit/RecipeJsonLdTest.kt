package com.dugcanlift.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors `RecipeJSONLDTests` in the iOS build. The two readers must agree on
 * the same page, because a recipe imported on one phone can reach the other
 * through the shared wire format and a field read differently on each side is a
 * bug nobody would think to look for.
 */
class RecipeJsonLdTest {

    private fun page(json: String): String =
        """<html><head><script type="application/ld+json">$json</script></head><body></body></html>"""

    // MARK: - Finding the node

    @Test
    fun `reads a recipe out of a page`() {
        val found = RecipeJsonLd.recipeFromHtml(
            page(
                """
                {"@type":"Recipe","name":"Lasagne",
                 "recipeIngredient":["500 g beef mince","2 tbsp olive oil"],
                 "recipeInstructions":["Brown the mince","Layer and bake"],
                 "recipeYield":"6 servings"}
                """.trimIndent()
            )
        )

        assertNotNull(found)
        assertEquals("Lasagne", found!!.name)
        assertEquals(listOf("500 g beef mince", "2 tbsp olive oil"), found.ingredientLines)
        assertEquals(listOf("Brown the mince", "Layer and bake"), found.steps)
        assertEquals(6.0, found.servings!!, 1e-9)
    }

    @Test
    fun `skips non-recipe blocks and keeps looking`() {
        val html = """
            <html><head>
            <script type="application/ld+json">{"@type":"WebSite","name":"A blog"}</script>
            <script type="application/ld+json">{"@type":"Recipe","name":"Soup"}</script>
            </head></html>
        """.trimIndent()

        assertEquals("Soup", RecipeJsonLd.recipeFromHtml(html)?.name)
    }

    @Test
    fun `finds a recipe inside a graph`() {
        val found = RecipeJsonLd.recipeFromJson(
            """{"@graph":[{"@type":"Article","name":"Post"},{"@type":"Recipe","name":"Stew"}]}"""
        )
        assertEquals("Stew", found?.name)
    }

    @Test
    fun `accepts an array of types`() {
        val found = RecipeJsonLd.recipeFromJson("""{"@type":["Article","Recipe"],"name":"Pie"}""")
        assertEquals("Pie", found?.name)
    }

    @Test
    fun `a page with no recipe returns null rather than an empty recipe`() {
        assertNull(RecipeJsonLd.recipeFromHtml(page("""{"@type":"WebSite","name":"A blog"}""")))
        assertNull(RecipeJsonLd.recipeFromHtml("<html><body>no json-ld here</body></html>"))
    }

    @Test
    fun `a recipe with no name is not a recipe`() {
        assertNull(RecipeJsonLd.recipeFromJson("""{"@type":"Recipe","recipeYield":"4"}"""))
    }

    // MARK: - Instructions

    @Test
    fun `reads HowToStep objects`() {
        val found = RecipeJsonLd.recipeFromJson(
            """
            {"@type":"Recipe","name":"Eggs","recipeInstructions":[
              {"@type":"HowToStep","text":"Crack them"},
              {"@type":"HowToStep","text":"Whisk them"}]}
            """.trimIndent()
        )
        assertEquals(listOf("Crack them", "Whisk them"), found?.steps)
    }

    @Test
    fun `flattens HowToSections`() {
        val found = RecipeJsonLd.recipeFromJson(
            """
            {"@type":"Recipe","name":"Cake","recipeInstructions":[
              {"@type":"HowToSection","name":"Sponge","itemListElement":[
                {"@type":"HowToStep","text":"Cream the butter"}]},
              {"@type":"HowToSection","name":"Icing","itemListElement":[
                {"@type":"HowToStep","text":"Beat the sugar"}]}]}
            """.trimIndent()
        )
        assertEquals(listOf("Cream the butter", "Beat the sugar"), found?.steps)
    }

    /**
     * Splitting on sentences would cut "Bake at 200 C. for 20 minutes" in half,
     * and an over-long step is far easier to fix than one quietly cut in two.
     */
    @Test
    fun `a single paragraph splits on newlines only`() {
        val found = RecipeJsonLd.recipeFromJson(
            """{"@type":"Recipe","name":"Toast","recipeInstructions":"Bake at 200 C. for 20 minutes.\nSlice it."}"""
        )
        assertEquals(listOf("Bake at 200 C. for 20 minutes.", "Slice it."), found?.steps)
    }

    @Test
    fun `strips markup and entities from text`() {
        val found = RecipeJsonLd.recipeFromJson(
            """{"@type":"Recipe","name":"Salt &amp; Pepper Squid","recipeInstructions":["<p>Fry it</p>"]}"""
        )
        assertEquals("Salt & Pepper Squid", found?.name)
        assertEquals(listOf("Fry it"), found?.steps)
    }

    // MARK: - Yield

    @Test
    fun `reads servings from the usual spellings`() {
        val cases = mapOf(
            """"4"""" to 4.0,
            """"4 servings"""" to 4.0,
            """"Serves 6"""" to 6.0,
            """8""" to 8.0,
            """["4","4 servings"]""" to 4.0
        )
        for ((yield, expected) in cases) {
            val found = RecipeJsonLd.recipeFromJson(
                """{"@type":"Recipe","name":"X","recipeYield":$yield}"""
            )
            assertEquals("failed on $yield", expected, found?.servings!!, 1e-9)
        }
    }

    @Test
    fun `a yield with no number stays null rather than defaulting to one`() {
        for (yield in listOf(""""a crowd"""", """"plenty"""", "true", "0")) {
            val found = RecipeJsonLd.recipeFromJson(
                """{"@type":"Recipe","name":"X","recipeYield":$yield}"""
            )
            assertNull("failed on $yield", found?.servings)
        }
    }

    // MARK: - Durations

    @Test
    fun `reads ISO 8601 durations`() {
        val cases = mapOf(
            "PT30M" to 30,
            "PT1H15M" to 75,
            "P0DT0H30M" to 30,
            "PT2H" to 120,
            "P1D" to 1440
        )
        for ((iso, expected) in cases) {
            val found = RecipeJsonLd.recipeFromJson(
                """{"@type":"Recipe","name":"X","prepTime":"$iso"}"""
            )
            assertEquals("failed on $iso", expected, found?.prepMinutes)
        }
    }

    /**
     * A leading "M" outside the time section is months. Reading it as minutes
     * would be a silent thirty-fold error.
     */
    @Test
    fun `months are ignored rather than read as minutes`() {
        val found = RecipeJsonLd.recipeFromJson(
            """{"@type":"Recipe","name":"X","prepTime":"P2M"}"""
        )
        assertNull(found?.prepMinutes)
    }

    @Test
    fun `a duration that is not a duration stays null`() {
        val found = RecipeJsonLd.recipeFromJson(
            """{"@type":"Recipe","name":"X","prepTime":"half an hour"}"""
        )
        assertNull(found?.prepMinutes)
    }

    // MARK: - Author

    @Test
    fun `reads author as string or person`() {
        assertEquals(
            "Nigel",
            RecipeJsonLd.recipeFromJson("""{"@type":"Recipe","name":"X","author":"Nigel"}""")?.author
        )
        assertEquals(
            "Nigella",
            RecipeJsonLd.recipeFromJson(
                """{"@type":"Recipe","name":"X","author":{"@type":"Person","name":"Nigella"}}"""
            )?.author
        )
        assertEquals(
            "Delia",
            RecipeJsonLd.recipeFromJson(
                """{"@type":"Recipe","name":"X","author":[{"@type":"Person","name":"Delia"}]}"""
            )?.author
        )
    }

    // MARK: - Nutrition

    @Test
    fun `reads nutrition as per serving`() {
        val found = RecipeJsonLd.recipeFromJson(
            """
            {"@type":"Recipe","name":"X","nutrition":{
              "@type":"NutritionInformation",
              "calories":"350 calories",
              "proteinContent":"12 g",
              "carbohydrateContent":"40 g",
              "fatContent":"15 g",
              "fiberContent":"3 g"}}
            """.trimIndent()
        )

        val macros = found?.nutritionPerServing
        assertNotNull(macros)
        assertEquals(350.0, macros!!.calories, 1e-9)
        assertEquals(12.0, macros.proteinG, 1e-9)
        assertEquals(40.0, macros.carbsG, 1e-9)
        assertEquals(15.0, macros.fatG, 1e-9)
        assertEquals(3.0, macros.fiberG, 1e-9)
        // The publisher's claim, never a figure resolved against the food
        // database. Every screen that shows it must say so.
        assertTrue(macros.estimated)
    }

    @Test
    fun `nutrition without calories is dropped`() {
        val found = RecipeJsonLd.recipeFromJson(
            """{"@type":"Recipe","name":"X","nutrition":{"proteinContent":"12 g"}}"""
        )
        assertNull(found?.nutritionPerServing)
    }

    @Test
    fun `thousands separators are read`() {
        val found = RecipeJsonLd.recipeFromJson(
            """{"@type":"Recipe","name":"X","nutrition":{"calories":"1,200 calories"}}"""
        )
        assertEquals(1200.0, found?.nutritionPerServing!!.calories, 1e-9)
    }

    // MARK: - Ingredients

    @Test
    fun `reads the pre-2017 ingredients spelling`() {
        val found = RecipeJsonLd.recipeFromJson(
            """{"@type":"Recipe","name":"X","ingredients":["100 g rice"]}"""
        )
        assertEquals(listOf("100 g rice"), found?.ingredientLines)
    }

    /**
     * The lines are handed over raw. Quantities stay [IngredientParser]'s job,
     * and it still refuses to weigh a volume.
     */
    @Test
    fun `ingredient lines survive verbatim for the parser`() {
        val found = RecipeJsonLd.recipeFromJson(
            """{"@type":"Recipe","name":"X","recipeIngredient":["1 1/2 cups oats","a handful of nuts"]}"""
        )!!

        val oats = IngredientParser.parse(found.ingredientLines[0])
        assertEquals(1.5, oats.qty!!, 1e-9)
        assertEquals("cups", oats.unit)

        val nuts = IngredientParser.parse(found.ingredientLines[1])
        assertNull(nuts.qty)
    }

    @Test
    fun `the source transcript survives the import`() {
        val json = """{"@type":"Recipe","name":"Lasagne"}"""
        val found = RecipeJsonLd.recipeFromJson(json)
        assertEquals(json, found?.sourceTranscript)
    }

    // MARK: - Handing a caption to the same shape

    @Test
    fun `a parsed caption converts to an import with no macros`() {
        val parsed = CaptionRecipe.parse("Ingredients\n2 eggs")
        val imported = parsed.imported(
            name = "Scramble",
            ingredientLines = listOf("2 eggs", "10 g butter"),
            steps = listOf("Whisk"),
            servings = 2.0
        )

        assertEquals("Scramble", imported.name)
        assertEquals(listOf("2 eggs", "10 g butter"), imported.ingredientLines)
        assertEquals(2.0, imported.servings!!, 1e-9)
        // A caption states none, so the app's costing pass fills the gap
        // exactly as it does for a page that published none.
        assertNull(imported.nutritionPerServing)
        assertTrue(imported.sourceTranscript.contains("2 eggs"))
    }
}
