package com.dugcanlift.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same fixtures as the iOS build's `CaptionRecipeTests`, deliberately:
 * they are shaped like real captions rather than ideal input, and running the
 * identical input through both ports is how the two stay recognisably the same
 * feature.
 */
class CaptionRecipeTest {

    // MARK: - The common case

    @Test
    fun `labelled caption splits on its own headings`() {
        val parsed = CaptionRecipe.parse(
            """
            Creamy Tuscan Chicken 🍗

            Ingredients:
            - 2 chicken breasts
            - 1 cup heavy cream
            - 200g spinach

            Method:
            1. Sear the chicken
            2. Add the cream and wilt the spinach

            #mealprep #highprotein
            """.trimIndent()
        )

        assertEquals(Split.LABELLED, parsed.split)
        assertEquals("Creamy Tuscan Chicken 🍗", parsed.name)
        assertEquals(
            listOf("2 chicken breasts", "1 cup heavy cream", "200g spinach"),
            parsed.ingredientLines
        )
        assertEquals(
            listOf("Sear the chicken", "Add the cream and wilt the spinach"),
            parsed.steps
        )
    }

    @Test
    fun `headings are found through decoration`() {
        val parsed = CaptionRecipe.parse(
            """
            🛒 INGREDIENTS 👇
            2 eggs
            — Instructions —
            Whisk them
            """.trimIndent()
        )

        assertEquals(Split.LABELLED, parsed.split)
        assertEquals(listOf("2 eggs"), parsed.ingredientLines)
        assertEquals(listOf("Whisk them"), parsed.steps)
    }

    @Test
    fun `what youll need is an ingredient heading`() {
        val parsed = CaptionRecipe.parse(
            """
            What you'll need:
            1 banana
            How to make it:
            Mash it
            """.trimIndent()
        )

        assertEquals(Split.LABELLED, parsed.split)
        assertEquals(listOf("1 banana"), parsed.ingredientLines)
        assertEquals(listOf("Mash it"), parsed.steps)
    }

    // MARK: - Bullets and numbering

    @Test
    fun `list numbering is stripped but decimal quantities survive`() {
        // "1.5 cups" losing its "1." would silently become five cups.
        val parsed = CaptionRecipe.parse(
            """
            Ingredients
            1. 1.5 cups flour
            2) 2 eggs
            """.trimIndent()
        )

        assertEquals(listOf("1.5 cups flour", "2 eggs"), parsed.ingredientLines)
    }

    @Test
    fun `mixed bullets are removed`() {
        val parsed = CaptionRecipe.parse(
            """
            Ingredients
            • 100 g rice
            ▢ 1 tbsp oil
            * 2 cloves garlic
            """.trimIndent()
        )

        assertEquals(
            listOf("100 g rice", "1 tbsp oil", "2 cloves garlic"),
            parsed.ingredientLines
        )
    }

    // MARK: - Partial and absent structure

    @Test
    fun `only an ingredient heading is inferred`() {
        val parsed = CaptionRecipe.parse(
            """
            Ingredients:
            2 eggs
            1 cup milk
            """.trimIndent()
        )

        assertEquals(Split.INFERRED, parsed.split)
        assertEquals(listOf("2 eggs", "1 cup milk"), parsed.ingredientLines)
        assertTrue(parsed.steps.isEmpty())
    }

    @Test
    fun `only a method heading takes the ingredients from above`() {
        val parsed = CaptionRecipe.parse(
            """
            Garlic Butter Rice
            200 g rice
            2 cloves garlic
            Method
            Boil the rice
            """.trimIndent()
        )

        assertEquals(Split.INFERRED, parsed.split)
        assertEquals("Garlic Butter Rice", parsed.name)
        assertEquals(listOf("200 g rice", "2 cloves garlic"), parsed.ingredientLines)
        assertEquals(listOf("Boil the rice"), parsed.steps)
    }

    @Test
    fun `method first is still labelled`() {
        val parsed = CaptionRecipe.parse(
            """
            Instructions:
            Melt the butter
            Ingredients:
            50 g butter
            """.trimIndent()
        )

        assertEquals(Split.LABELLED, parsed.split)
        assertEquals(listOf("Melt the butter"), parsed.steps)
        assertEquals(listOf("50 g butter"), parsed.ingredientLines)
    }

    @Test
    fun `unstructured paste is unsorted rather than guessed at`() {
        val parsed = CaptionRecipe.parse(
            """
            Banana Pancakes
            2 eggs
            1 banana
            Blend and fry
            """.trimIndent()
        )

        assertEquals(Split.UNSORTED, parsed.split)
        assertEquals("Banana Pancakes", parsed.name)
        // Everything lands on one side. The editor cuts the method out; the
        // parser does not pretend to know where it starts.
        assertEquals(listOf("2 eggs", "1 banana", "Blend and fry"), parsed.ingredientLines)
        assertTrue(parsed.steps.isEmpty())
    }

    @Test
    fun `empty input`() {
        val parsed = CaptionRecipe.parse("")
        assertTrue(parsed.isEmpty)
        assertNull(parsed.name)
        assertEquals(Split.UNSORTED, parsed.split)
    }

    // MARK: - Servings

    @Test
    fun `explicit yield is read`() {
        for (line in listOf("Serves 4", "Servings: 4", "Makes about 4 bowls", "Feeds 4")) {
            val parsed = CaptionRecipe.parse("Ingredients\n2 eggs\n$line")
            assertEquals("failed on $line", 4.0, parsed.servings!!, 1e-9)
            assertEquals(
                "the yield line must not also be an ingredient",
                listOf("2 eggs"), parsed.ingredientLines
            )
        }
    }

    /**
     * The exact line that made yield inference a bad idea: it is a cooking
     * time, and reading it as a yield halves every macro in the dish.
     */
    @Test
    fun `a cooking time is not a yield`() {
        val parsed = CaptionRecipe.parse(
            """
            Ingredients
            1 ham bone
            Method
            Cook slowly with the ham bone for 2 hours
            """.trimIndent()
        )

        assertNull(parsed.servings)
    }

    @Test
    fun `a yield word without a number is not a yield`() {
        val parsed = CaptionRecipe.parse("Ingredients\n100 g rice\nServes with rice")
        assertNull(parsed.servings)
    }

    // MARK: - The name

    @Test
    fun `a quantity line is never the name`() {
        val parsed = CaptionRecipe.parse("2 eggs\n1 banana\nBlend")
        assertNull(parsed.name)
        assertEquals(listOf("2 eggs", "1 banana", "Blend"), parsed.ingredientLines)
    }

    @Test
    fun `the name is not also an ingredient`() {
        val parsed = CaptionRecipe.parse("Banana Pancakes\n2 eggs")
        assertEquals("Banana Pancakes", parsed.name)
        assertEquals(listOf("2 eggs"), parsed.ingredientLines)
    }

    @Test
    fun `a line below the heading is never the name`() {
        val parsed = CaptionRecipe.parse("Ingredients\nSalt\nPepper")
        assertNull(parsed.name)
        assertEquals(listOf("Salt", "Pepper"), parsed.ingredientLines)
    }

    // MARK: - Noise

    @Test
    fun `hashtag and emoji lines are dropped`() {
        val parsed = CaptionRecipe.parse(
            """
            Ingredients
            2 eggs
            🔥🔥🔥
            #foodie #mealprep
            @somecreator
            """.trimIndent()
        )

        assertEquals(listOf("2 eggs"), parsed.ingredientLines)
    }

    // MARK: - Reparsing an edited box

    @Test
    fun `lines strips bullets and blanks`() {
        val lines = CaptionRecipe.lines(
            """
            - 2 eggs

            • 1 cup milk

            3. 200 g flour
            """.trimIndent()
        )

        assertEquals(listOf("2 eggs", "1 cup milk", "200 g flour"), lines)
    }

    // MARK: - Handing over to the parser that reads quantities

    /**
     * The parser only splits; quantities stay [IngredientParser]'s job, and it
     * still refuses to guess at a volume it cannot weigh.
     */
    @Test
    fun `quantities are still the parsers job on save`() {
        val parsed = CaptionRecipe.parse("Ingredients\n- 1 1/2 cups oats\n- a handful of nuts")

        val oats = IngredientParser.parse(parsed.ingredientLines[0])
        assertEquals(1.5, oats.qty!!, 1e-9)
        assertEquals("cups", oats.unit)

        val nuts = IngredientParser.parse(parsed.ingredientLines[1])
        assertNull(nuts.qty)
    }
}
