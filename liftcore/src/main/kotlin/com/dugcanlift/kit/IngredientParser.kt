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
 * Both, and the two web builds, are checked against dugcanlift-kit's
 * `data/ingredient-lines.json`, whose `data/README.md` has the rules.
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

    /**
     * Every whitespace character, written out rather than left to
     * [Char.isWhitespace]: U+0009-U+000D, U+0020, U+00A0, U+1680,
     * U+2000-U+200A, U+2028, U+2029, U+202F, U+205F, U+3000, U+FEFF
     * (JavaScript's `\s`). Before parsing, each one becomes a plain space, so
     * a non-breaking space or a tab pasted from a recipe page splits words like
     * a space does. [RecipeIngredient.rawText] keeps the line as it was. The
     * same set, character for character, is in all four parsers.
     */
    private fun isListedWhitespace(c: Char): Boolean = when (c.code) {
        in 0x09..0x0D, 0x20, 0xA0, 0x1680, in 0x2000..0x200A,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
        else -> false
    }

    /** The line with every character in the listed set replaced by U+0020. */
    internal fun normalizeWhitespace(raw: String): String =
        buildString(raw.length) { raw.forEach { append(if (isListedWhitespace(it)) ' ' else it) } }

    /**
     * The vulgar fractions read as quantities, as numerator to denominator:
     * the eighteen in Unicode that name a fraction, alone ("½ cup") or after a
     * whole number with or without a space ("1½", "1 ½"). Not ⅟ (U+215F),
     * which is half of a fraction, or ↉ (U+2189), zero thirds. The same
     * eighteen are in all four parsers.
     */
    private val VULGAR_FRACTIONS = mapOf(
        '¼' to (1.0 to 4.0), '½' to (1.0 to 2.0), '¾' to (3.0 to 4.0),
        '⅐' to (1.0 to 7.0), '⅑' to (1.0 to 9.0), '⅒' to (1.0 to 10.0), '⅓' to (1.0 to 3.0), '⅔' to (2.0 to 3.0),
        '⅕' to (1.0 to 5.0), '⅖' to (2.0 to 5.0), '⅗' to (3.0 to 5.0), '⅘' to (4.0 to 5.0), '⅙' to (1.0 to 6.0), '⅚' to (5.0 to 6.0),
        '⅛' to (1.0 to 8.0), '⅜' to (3.0 to 8.0), '⅝' to (5.0 to 8.0), '⅞' to (7.0 to 8.0)
    )

    /** The slash and U+2044 FRACTION SLASH, which reads as one. */
    private val SLASHES = setOf('/', '\u2044')

    /**
     * Characters that join a number to another one: `.`, `,`, and the dashes
     * - ‐ ‑ ‒ – — − (U+002D, U+2010-U+2014, U+2212). See [Reader.endsCleanly].
     */
    private val JOINERS = setOf('.', ',', '-', '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2212')

    /**
     * The punctuation trimmed from the end of a unit word, so "2 tbsp. oil",
     * "2 tbsp: oil" and "1 cup; sugar" all read the unit. Only the end, and
     * only these four: "(tbsp)", "tbsp)" and "tbsp-" are not units. The same
     * four are in all four parsers.
     */
    private val UNIT_TRAILING_PUNCTUATION = charArrayOf('.', ',', ':', ';')

    /**
     * Unicode category N — a digit, a vulgar fraction, a superscript, another
     * script's digit — by code point, so a digit outside the BMP counts too:
     * the same set JavaScript's `\p{N}` and Swift's general category read.
     */
    private fun isNumberCharacter(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    fun parse(raw: String): RecipeIngredient {
        // Every trim below is of plain spaces only: the line is already
        // normalized, and trim() would also strip U+001C-U+001F.
        val reader = Reader(normalizeWhitespace(raw))
        val quantity = reader.takeQuantity()
            ?: return RecipeIngredient(rawText = raw)

        var unit: String? = null
        val remainder = reader.rest()
        val firstWord = remainder.substringBefore(' ')
        val candidate = firstWord.trimEnd(*UNIT_TRAILING_PUNCTUATION).lowercase()

        val known = if (candidate in UNITS) candidate else SPELLED_UNITS[candidate]

        val item: String
        if (known != null) {
            unit = known
            item = remainder.substringAfter(' ', "").trim(' ')
        } else {
            item = remainder.trim(' ')
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

    /**
     * Reads the quantity off the front of the line. A quantity is a decimal
     * ("2", "1.5", "1.", ".5"; ASCII digits and at most one point), a fraction
     * of whole numbers ("1/2", the fraction slash U+2044 read as "/"), a whole
     * number then spaces then a fraction ("1 1/2"), or a vulgar fraction,
     * alone or after a whole number ("½", "1½", "1 ½"). Every number in it must
     * be finite and a denominator must not be zero.
     */
    private class Reader(text: String) {
        private var s = text.trimStart(' ')

        fun rest(): String = s.trimStart(' ')

        private fun digits(from: String): String = from.takeWhile { it in '0'..'9' }

        /** A run of digits as a number, or null when it is too long to be finite. */
        private fun number(run: String): Double? = run.toDoubleOrNull()?.takeIf { it.isFinite() }

        private fun vulgar(from: String): Double? =
            from.firstOrNull()?.let { VULGAR_FRACTIONS[it] }?.let { (n, d) -> n / d }

        /** "/" or U+2044 and a nonzero whole denominator, consumed only if all there. */
        private fun takeDenominator(from: String): Pair<Double, String>? {
            if (from.firstOrNull() !in SLASHES) return null
            val run = digits(from.drop(1))
            val d = if (run.isEmpty()) null else number(run)
            if (d == null || d == 0.0) return null
            return d to from.drop(1 + run.length)
        }

        /** The quantity, or null to give up on the line. */
        fun takeQuantity(): Double? {
            var value: Double
            val lead = vulgar(s)
            if (lead != null) {
                s = s.drop(1)
                value = lead
            } else {
                val run = s.takeWhile { it in '0'..'9' || it == '.' }
                if (run.isEmpty()) return null
                s = s.drop(run.length)
                if ('.' in run) {
                    // One point at most, and at least one digit: "1.2.3" and
                    // "." are not numbers.
                    if (run.count { it == '.' } != 1 || run.length == 1) return null
                    value = number(run) ?: return null
                } else {
                    value = number(run) ?: return null
                    val denominator = takeDenominator(s)
                    val fraction = if (denominator == null) vulgar(s) else null
                    if (denominator != null) {
                        value /= denominator.first
                        s = denominator.second
                    } else if (fraction != null) {
                        s = s.drop(1)
                        value += fraction
                    } else if (s.startsWith(" ")) {
                        // "1 1/2" or "1 ½" — a whole number, spaces, then a fraction.
                        val after = s.trimStart(' ')
                        val spaced = vulgar(after)
                        val numerator = digits(after)
                        if (spaced != null) {
                            s = after.drop(1)
                            value += spaced
                        } else if (numerator.isNotEmpty()) {
                            val n = number(numerator)
                            val mixed = if (n == null) null else takeDenominator(after.drop(numerator.length))
                            if (n != null && mixed != null) {
                                value += n / mixed.first
                                s = mixed.second
                            }
                        }
                    }
                }
            }

            if (!value.isFinite() || !endsCleanly()) return null
            s = s.trimStart(' ')
            return value
        }

        /**
         * Whether the quantity may end here. Not when the next character after
         * any spaces is a number character or a slash, or is one of [JOINERS]
         * followed, after any spaces, by a number character: "1 2 eggs", "1,5
         * kg", "1,000 g", "1-2 cups", "1/2/3", "2² cups". Each of those is a
         * number this parser cannot read, and stopping short of it would be a
         * confident wrong quantity. A dash before a letter is fine: "1/2-inch
         * piece ginger".
         */
        private fun endsCleanly(): Boolean {
            val next = s.trimStart(' ')
            if (next.isEmpty()) return true
            val c = next[0]
            if (isNumberCharacter(next.codePointAt(0)) || c in SLASHES) return false
            if (c in JOINERS) {
                val after = next.drop(1).trimStart(' ')
                if (after.isNotEmpty() && isNumberCharacter(after.codePointAt(0))) return false
            }
            return true
        }
    }
}
