package com.dugcanlift.kit

/**
 * Pulls a quantity and unit off the front of a typed ingredient line.
 *
 * Deliberately small. It handles the shapes people actually type and gives up
 * cleanly on everything else, leaving [RecipeIngredient.item] null so the
 * shopping list shows the raw line instead. Guessing harder here would produce
 * confident wrong quantities, which is worse than an unparsed line the reader
 * can see and check.
 *
 * Mirrors `IngredientParser` in the iOS build. The two must agree, because a
 * recipe written on one phone can reach the other through the shared wire
 * format — including [COUNT_UNIT], which the shopping list must never print.
 */
object IngredientParser {

    /**
     * Grouping key for an ingredient with no unit — "2 eggs", "1 banana".
     *
     * A sentinel, not a unit. It keeps counts in their own bucket during
     * aggregation, so two cloves of garlic are never added to two cups of
     * anything, and the shopping list drops it when printing, because
     * "2 x banana" is not how anyone writes a shopping list.
     *
     * Contains a null character so it can never collide with something typed.
     */
    const val COUNT_UNIT = "\u0000count"

    private val UNITS = setOf(
        "g", "kg", "mg", "ml", "l",
        "tsp", "tbsp", "cup", "cups", "oz", "lb", "lbs",
        "clove", "cloves", "slice", "slices", "scoop", "scoops",
        "can", "cans", "pinch", "handful"
    )

    /**
     * Unit words spelled out, as US recipe sites write them ("1.5 pounds
     * ground beef"), each stored as the abbreviation in [UNITS]. Storing the
     * abbreviation is the whole design: costing, shopping-list grouping and
     * [COUNT_UNIT] already key on it, so a spelled-out line behaves exactly
     * like its abbreviated twin and nothing downstream changes.
     *
     * The same table, word for word, is in the iOS kit's
     * `IngredientParser.swift`, Coach web's `coach/parser.js` and LIFT web's
     * `app.js`. Deliberately not here: `fl oz`, a bare `t` or `T`, `c.`,
     * `pt`, `qt`, `stick`, or anything that needs a density.
     */
    private val SPELLED_UNITS = mapOf(
        "gram" to "g", "grams" to "g", "gramme" to "g", "grammes" to "g",
        "kilogram" to "kg", "kilograms" to "kg", "kilogramme" to "kg", "kilogrammes" to "kg",
        "kilo" to "kg", "kilos" to "kg",
        "milligram" to "mg", "milligrams" to "mg", "milligramme" to "mg", "milligrammes" to "mg",
        "ounce" to "oz", "ounces" to "oz",
        "pound" to "lb", "pounds" to "lb",
        "teaspoon" to "tsp", "teaspoons" to "tsp",
        "tablespoon" to "tbsp", "tablespoons" to "tbsp",
        "milliliter" to "ml", "milliliters" to "ml", "millilitre" to "ml", "millilitres" to "ml",
        "liter" to "l", "liters" to "l", "litre" to "l", "litres" to "l"
    )

    fun parse(raw: String): RecipeIngredient {
        val reader = Reader(raw)
        val quantity = reader.takeQuantity()
            ?: return RecipeIngredient(rawText = raw)

        var unit: String? = null
        val remainder = reader.rest()
        val firstWord = remainder.substringBefore(' ')
        val candidate = firstWord.lowercase().trim('.', ',')

        val known = if (candidate in UNITS) candidate else SPELLED_UNITS[candidate]

        val item: String
        if (known != null) {
            unit = known
            item = remainder.substringAfter(' ', "").trim()
        } else {
            item = remainder.trim()
        }

        if (item.isEmpty()) return RecipeIngredient(rawText = raw)

        return RecipeIngredient(
            rawText = raw,
            item = item,
            qty = quantity,
            unit = unit ?: COUNT_UNIT,
            grams = if (unit == "g") quantity else null
        )
    }

    /** Reads a leading number, including "1/2" and "1 1/2". */
    private class Reader(text: String) {
        private var s = text.trimStart()

        fun rest(): String = s.trimStart()

        private fun takeNumber(): Double? {
            val digits = s.takeWhile { it.isDigit() || it == '.' }
            if (digits.isEmpty()) return null
            val value = digits.toDoubleOrNull() ?: return null
            s = s.drop(digits.length)
            return value
        }

        fun takeQuantity(): Double? {
            var value = takeNumber() ?: return null

            if (s.startsWith("/")) {
                s = s.drop(1)
                val denominator = takeNumber()
                if (denominator == null || denominator == 0.0) return null
                value /= denominator
            } else if (s.startsWith(" ")) {
                // "1 1/2" — a whole number followed by a fraction.
                val saved = s
                s = s.drop(1)
                val whole = takeNumber()
                if (whole != null && s.startsWith("/")) {
                    s = s.drop(1)
                    val denominator = takeNumber()
                    if (denominator != null && denominator != 0.0) {
                        value += whole / denominator
                    } else {
                        s = saved
                    }
                } else {
                    s = saved
                }
            }

            s = s.trimStart()
            return value
        }
    }
}
