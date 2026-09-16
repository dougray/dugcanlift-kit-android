package com.dugcanlift.kit

/**
 * Splits a pasted block of prose — a social caption, an email, a card retyped —
 * into a recipe's parts.
 *
 * Mirrors `CaptionRecipe` in the iOS build, and exists for the same reason: a
 * page's schema.org JSON-LD can be read because a publisher labelled it, and
 * social video never does. TikTok, Instagram and Reels publish no
 * schema.org Recipe at all, YouTube publishes a VideoObject, and all of them
 * hand a plain fetch a JavaScript shell. The recipe there is prose in a
 * caption.
 *
 * Deliberately much less ambitious than a labelled reader. This only ever
 * **proposes a split**, and the screen that uses it is an editor rather than a
 * review. Getting the split wrong costs the reader an edit; it must never cost
 * them a number.
 *
 * That is the whole safety argument. Nothing here invents a quantity, a unit or
 * a macro — [IngredientParser] still does the quantities, on save, from
 * whatever text the reader finally approved, and it still refuses to guess.
 * This decides only which lines to put in front of them.
 *
 * ### On parity
 *
 * [IngredientParser] must agree byte for byte across four builds, because a
 * line travels between clients through the shared wire format. This is an
 * *input* path, not a wire format: what it produces is indistinguishable from a
 * hand-typed recipe the moment it is saved. Agreement here is a convenience for
 * whoever reads both, not a contract — but the rules below were ported rather
 * than reinvented, and the tests are the same fixtures.
 */
object CaptionRecipe {

    /**
     * Proposes a split of pasted text into name, ingredients and method.
     *
     * Never fails: text with no recognisable structure comes back
     * [Split.UNSORTED] with every content line in [ParsedCaption.ingredientLines],
     * which is the honest answer and the one an editor can act on.
     */
    fun parse(text: String): ParsedCaption {
        val rows = text.split('\n', '\r')
            .map { Row(it) }
            .filter { !it.isNoise }

        var ingredientsAt: Int? = null
        var methodAt: Int? = null
        rows.forEachIndexed { index, row ->
            when (row.heading) {
                Heading.INGREDIENTS -> if (ingredientsAt == null) ingredientsAt = index
                Heading.METHOD -> if (methodAt == null) methodAt = index
                null -> Unit
            }
        }

        val servings = rows.firstNotNullOfOrNull { it.statedServings }
        val name = title(rows)

        // Anything already spoken for by the header scan must not also be
        // offered as content -- the name line most of all, which is otherwise
        // the first "ingredient" of every unsorted paste.
        fun content(slice: List<Row>): List<String> =
            slice.filter { it.heading == null && it.statedServings == null && it.text != name }
                .map { it.text }

        val ingredientLines: List<String>
        val steps: List<String>
        val split: Split

        val ing = ingredientsAt
        val met = methodAt
        when {
            ing != null && met != null && ing < met -> {
                ingredientLines = content(rows.subList(ing + 1, met))
                steps = content(rows.subList(met + 1, rows.size))
                split = Split.LABELLED
            }
            ing != null && met != null -> {
                // Method first. Rare, but some writers lead with the story and
                // list what to buy underneath, and the headings say so plainly.
                steps = content(rows.subList(met + 1, ing))
                ingredientLines = content(rows.subList(ing + 1, rows.size))
                split = Split.LABELLED
            }
            ing != null -> {
                ingredientLines = content(rows.subList(ing + 1, rows.size))
                steps = emptyList()
                split = Split.INFERRED
            }
            met != null -> {
                // Everything above a method heading is the shopping side of it.
                // That is an inference, so it is labelled as one.
                ingredientLines = content(rows.subList(0, met))
                steps = content(rows.subList(met + 1, rows.size))
                split = Split.INFERRED
            }
            else -> {
                ingredientLines = content(rows)
                steps = emptyList()
                split = Split.UNSORTED
            }
        }

        return ParsedCaption(
            name = name,
            ingredientLines = ingredientLines,
            steps = steps,
            servings = servings,
            split = split,
            sourceText = text.trim()
        )
    }

    /**
     * Turns an edited text box back into lines.
     *
     * The save path, and the reason the editor can be two plain text boxes: the
     * raw text is the contract and reparsing is how it stays one.
     */
    fun lines(text: String): List<String> =
        text.split('\n', '\r').map { Row.strip(it) }.filter { it.isNotEmpty() }

    /**
     * The **first** line, and only that line, when it reads like a title.
     *
     * Scanning further down for "something titular" looks more generous and is
     * strictly worse: in a caption that opens with a method heading it picks up
     * the first instruction, and in an unstructured paste it picks up whichever
     * line happens not to start with a number -- usually the method. A title
     * being at the top is stated structure; a title being somewhere in the
     * middle is a guess, and a wrong one takes a real line out of the recipe
     * with it.
     */
    private fun title(rows: List<Row>): String? {
        val first = rows.firstOrNull() ?: return null
        if (first.heading != null) return null
        if (first.statedServings != null) return null
        if (!first.looksLikeTitle) return null
        return first.text
    }

    internal enum class Heading { INGREDIENTS, METHOD }

    /** One line of the paste. */
    internal class Row(raw: String) {
        val text: String = strip(raw)
        val heading: Heading?
        val statedServings: Double?

        init {
            val words = significantWords(text)
            heading = heading(words)
            statedServings = servings(text, words)
        }

        /**
         * Blank, or decoration with no words in it -- a row of emoji, a rule of
         * dashes, a line of nothing but hashtags.
         */
        val isNoise: Boolean
            get() {
                if (text.isEmpty()) return true
                val tokens = text.split(' ').filter { it.isNotEmpty() }
                if (tokens.isNotEmpty() && tokens.all { it.startsWith("#") || it.startsWith("@") }) {
                    return true
                }
                return significantWords(text).isEmpty()
            }

        /**
         * Short, wordy, and not opening with a quantity.
         *
         * The digit test is what keeps "2 chicken breasts" from being read as a
         * dish called "2 chicken breasts" -- almost every ingredient line starts
         * with its number, and almost no title does.
         */
        val looksLikeTitle: Boolean
            get() {
                val first = text.firstOrNull() ?: return false
                if (first.isDigit()) return false
                if (text.length > 80) return false
                return significantWords(text).size <= 12
            }

        companion object {
            private val BULLETS = setOf(
                '-', '–', '—', '*', '•', '‣', '·',
                '▢', '☐', '□', '▪', '▫', '●',
                '○', '+', '>', '~'
            )

            /**
             * Removes bullets and leading list numbering, keeping the content.
             *
             * Formatting, not content, so taking it off is not a guess. The
             * numbering strip requires whitespace after the separator, because
             * "1.5 cups flour" otherwise loses its "1." and becomes five cups.
             */
            fun strip(raw: String): String {
                var text = raw

                fun trimDecoration() {
                    var index = 0
                    while (index < text.length) {
                        val ch = text[index]
                        if (ch.isWhitespace() || ch in BULLETS || isDecorative(ch)) index++ else break
                    }
                    text = text.substring(index)
                }

                trimDecoration()

                val digits = text.takeWhile { it.isDigit() }
                if (digits.isNotEmpty()) {
                    val after = text.drop(digits.length)
                    val separator = after.firstOrNull()
                    val following = after.getOrNull(1)
                    if ((separator == '.' || separator == ')') &&
                        following != null && following.isWhitespace()
                    ) {
                        text = after.drop(1)
                        trimDecoration()
                    }
                }

                return text.trim()
            }

            /** Emoji and symbols used as decoration around a heading or bullet. */
            private fun isDecorative(ch: Char): Boolean {
                if (ch.isLetter() || ch.isDigit()) return false
                return when (Character.getType(ch)) {
                    Character.OTHER_SYMBOL.toInt(),
                    Character.MATH_SYMBOL.toInt(),
                    Character.MODIFIER_SYMBOL.toInt(),
                    Character.SURROGATE.toInt() -> true
                    else -> false
                }
            }

            /**
             * Letters only, lowercased, apostrophes dropped so "you'll" and
             * "youll" are one word. Digits and punctuation are not words.
             */
            fun significantWords(text: String): List<String> {
                val words = mutableListOf<String>()
                val word = StringBuilder()
                for (ch in text.lowercase()) {
                    when {
                        ch.isLetter() -> word.append(ch)
                        // Dropped rather than treated as a break, so "you'll"
                        // and "youll" are the same word.
                        ch == '\'' || ch == '’' -> Unit
                        else -> if (word.isNotEmpty()) {
                            words.add(word.toString())
                            word.clear()
                        }
                    }
                }
                if (word.isNotEmpty()) words.add(word.toString())
                return words
            }

            private val INGREDIENT_HEADINGS = setOf(
                "ingredients", "ingredient", "the ingredients", "ingredients list",
                "ingredient list", "what you need", "what youll need",
                "what you will need", "youll need", "you will need", "you need",
                "shopping list", "grocery list", "what to buy", "for the recipe"
            )

            private val METHOD_HEADINGS = setOf(
                "method", "the method", "instructions", "instruction", "directions",
                "direction", "steps", "the steps", "how to", "how to make",
                "how to make it", "how to make this", "how i make it", "preparation",
                "lets make it", "lets go", "process", "to make"
            )

            /**
             * A heading is a short line that says nothing but its own name.
             *
             * Matched on the words alone, so "INGREDIENTS:" and "— Ingredients —"
             * and "Ingredients 👇" are all the same heading.
             */
            fun heading(words: List<String>): Heading? {
                if (words.isEmpty() || words.size > 4) return null
                val phrase = words.joinToString(" ")
                return when (phrase) {
                    in INGREDIENT_HEADINGS -> Heading.INGREDIENTS
                    in METHOD_HEADINGS -> Heading.METHOD
                    else -> null
                }
            }

            private val YIELD_WORDS = setOf(
                "serves", "serving", "servings", "makes", "yield", "yields",
                "feeds", "portions"
            )

            /**
             * An explicitly stated yield, and nothing looser.
             *
             * The line must *open* with a yield word and carry a number. A bare
             * "for 2" is not accepted, because "cook slowly with the ham bone
             * for 2 hours" is a cooking time, and reading it as a yield halves
             * every macro in the dish. A wrong serving count is silent and
             * divides everything, so this is the strictest rule in the file.
             */
            fun servings(text: String, words: List<String>): Double? {
                val opening = words.firstOrNull() ?: return null
                if (opening !in YIELD_WORDS) return null
                if (words.size > 6) return null

                val rest = text.dropWhile { !it.isDigit() }
                val digits = rest.takeWhile { it.isDigit() || it == '.' }
                val value = digits.toDoubleOrNull() ?: return null
                if (value <= 0.0 || value > 200.0) return null
                return value
            }
        }
    }
}

/** How much structure the pasted text actually stated. */
enum class Split {
    /** Both headings found. The division is the writer's own. */
    LABELLED,

    /** One heading found; the other side was taken from what was left. */
    INFERRED,

    /**
     * No headings. Every content line is in `ingredientLines`, and the method
     * has to be cut out by hand.
     */
    UNSORTED
}

/**
 * A proposed split of pasted text, before the reader has approved any of it.
 *
 * Not a finished import, on purpose: this means "our best guess at what you
 * pasted", and [split] says how much of a guess it was.
 */
data class ParsedCaption(
    val name: String? = null,
    val ingredientLines: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    /**
     * Only ever from an explicit "serves 4". Null is left null rather than
     * defaulted to 1, so an editor can say the count is still unanswered.
     */
    val servings: Double? = null,
    val split: Split = Split.UNSORTED,
    /**
     * The paste verbatim, for the recipe's source transcript. What a misread
     * line is checked against afterwards.
     */
    val sourceText: String = ""
) {
    /** True when there is nothing worth showing an editor. */
    val isEmpty: Boolean get() = ingredientLines.isEmpty() && steps.isEmpty()
}
