package com.dugcanlift.kit

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a recipe out of a web page's schema.org JSON-LD block.
 *
 * Almost every recipe site publishes one, because Google requires it for a rich
 * result. That makes it the one import path that does not involve scraping a
 * layout: the publisher has already labelled the ingredients and the steps, and
 * we read the labels rather than guessing at markup.
 *
 * Mirrors `RecipeJSONLD` in the iOS build, field for field. Fetching the URL is
 * the app's job — this takes a string of already-fetched HTML and hands back a
 * value type, the same split the iOS copy uses so the widget-safe module stays
 * free of networking.
 *
 * The parse follows the same rule as [IngredientParser]: read what is plainly
 * stated, leave null for everything else. A publisher's nutrition block is a
 * claim, not a measurement, so it arrives flagged as estimated and nothing here
 * is ever auto-logged.
 *
 * ### Saturated fat, sugar and sodium
 *
 * Read as of 1.4.0, into [RecipeNutrition]'s optional fields: sugar and sodium
 * by the iOS rules exactly, including sodium's unit check. Saturated fat
 * (`saturatedFatContent`) is read here as well, the same way as every other
 * gram quantity; the iOS copy does not read it yet.
 */
object RecipeJsonLd {

    // MARK: - Entry points

    /**
     * Pulls the first schema.org Recipe out of a fetched HTML page.
     *
     * Returns null when the page publishes no JSON-LD, or publishes some that
     * contains no Recipe. Both are ordinary outcomes for a page that is not a
     * recipe, not errors worth surfacing.
     */
    fun recipeFromHtml(html: String): ImportedRecipe? {
        for (block in jsonLdBlocks(html)) {
            recipeFromJson(block)?.let { return it }
        }
        return null
    }

    /**
     * Parses one JSON-LD payload that has already been isolated.
     *
     * Separate from the HTML entry point so a caller holding JSON from an API —
     * or a test holding a fixture — does not have to wrap it in a fake page.
     */
    fun recipeFromJson(json: String): ImportedRecipe? {
        val root: Any = try {
            val trimmed = json.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        } catch (e: Exception) {
            return null
        }
        val node = findRecipeNode(root) ?: return null
        return build(node, json)
    }

    // MARK: - Locating the Recipe node

    /**
     * Finds the Recipe object inside a payload of any of the shapes sites use.
     *
     * Publishers wrap it three common ways: the bare object, a top-level array
     * of objects, and an `@graph` array holding the page's whole entity set.
     * Walking all three costs a few lines and removes a whole class of "works
     * on one blog, not the next".
     */
    private fun findRecipeNode(any: Any?): JSONObject? {
        when (any) {
            is JSONArray -> {
                for (index in 0 until any.length()) {
                    findRecipeNode(any.opt(index))?.let { return it }
                }
                return null
            }
            is JSONObject -> {
                if (isRecipe(any)) return any
                val graph = any.opt("@graph")
                if (graph != null) return findRecipeNode(graph)
                return null
            }
            else -> return null
        }
    }

    /**
     * `@type` is a string on most pages and an array on the ones that also
     * declare the page an Article, so both are accepted.
     */
    private fun isRecipe(node: JSONObject): Boolean {
        when (val type = node.opt("@type")) {
            is String -> return type.equals("Recipe", ignoreCase = true)
            is JSONArray -> {
                for (index in 0 until type.length()) {
                    if ((type.opt(index) as? String)?.equals("Recipe", ignoreCase = true) == true) {
                        return true
                    }
                }
                return false
            }
            else -> return false
        }
    }

    // MARK: - Field mapping

    private fun build(node: JSONObject, transcript: String): ImportedRecipe? {
        // A recipe with no name is not one we can show in a list, and every
        // real page has one. Bailing here keeps a half-read blob from reaching
        // the editor as an untitled recipe.
        val name = text(node.opt("name"))
        if (name.isNullOrEmpty()) return null

        return ImportedRecipe(
            name = name,
            ingredientLines = ingredientLines(node),
            steps = steps(node.opt("recipeInstructions")),
            servings = servings(node.opt("recipeYield")),
            prepMinutes = minutes(node.opt("prepTime")),
            cookMinutes = minutes(node.opt("cookTime")),
            author = author(node.opt("author")),
            nutritionPerServing = nutrition(node.opt("nutrition")),
            sourceTranscript = transcript
        )
    }

    /**
     * `recipeIngredient` is the current property; `ingredients` is the
     * pre-2017 spelling still emitted by older plugins.
     */
    private fun ingredientLines(node: JSONObject): List<String> =
        stringList(node.opt("recipeIngredient") ?: node.opt("ingredients"))

    /**
     * Instructions arrive as a paragraph, a list of strings, a list of
     * `HowToStep` objects, or `HowToSection`s holding those steps.
     *
     * A single paragraph is split on newlines only. Splitting on sentences
     * would cut "Bake at 200 C. for 20 minutes" in half, and a step that is too
     * long is a great deal easier for the reader to fix than a step that has
     * been quietly cut in two.
     */
    private fun steps(any: Any?): List<String> {
        if (any == null || any === JSONObject.NULL) return emptyList()

        if (any is String) {
            return clean(any).split('\n', '\r')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        if (any is JSONArray) {
            val out = mutableListOf<String>()
            for (index in 0 until any.length()) {
                when (val element = any.opt(index)) {
                    is String -> clean(element).takeIf { it.isNotEmpty() }?.let { out.add(it) }
                    is JSONObject -> {
                        // A section holds its own steps under itemListElement.
                        val nested = element.opt("itemListElement")
                        if (nested != null && nested !== JSONObject.NULL) {
                            out.addAll(steps(nested))
                        } else {
                            val step = text(element.opt("text")) ?: text(element.opt("name"))
                            if (!step.isNullOrEmpty()) out.add(step)
                        }
                    }
                }
            }
            return out
        }

        if (any is JSONObject) {
            val nested = any.opt("itemListElement")
            return steps(if (nested != null && nested !== JSONObject.NULL) nested else any.opt("text"))
        }
        return emptyList()
    }

    /**
     * `recipeYield` is "4", "4 servings", "Serves 4" or a bare number.
     *
     * The first number in the string wins. A yield with no number at all
     * ("1 loaf" parses, "a crowd" does not) stays null rather than defaulting
     * to 1, so the editor can ask instead of inventing a serving count that
     * every macro on the recipe would then be divided by.
     */
    private fun servings(any: Any?): Double? {
        if (any == null || any === JSONObject.NULL) return null
        numeric(any)?.let { return if (it > 0) it else null }

        val candidate: String? = when (any) {
            is String -> any
            is JSONArray -> (0 until any.length()).firstNotNullOfOrNull { text(any.opt(it)) }
            else -> null
        } ?: return null

        val value = firstNumber(candidate!!) ?: return null
        return if (value > 0) value else null
    }

    /**
     * ISO 8601 durations, the only form schema.org allows for these — "PT30M",
     * "PT1H15M", occasionally "P0DT0H30M".
     *
     * Written out rather than handed to a formatter so the result is the same
     * on every OS version, and so this and the iOS copy have something
     * unambiguous to match.
     */
    private fun minutes(any: Any?): Int? {
        val raw = text(any)
        if (raw.isNullOrEmpty()) return null

        val scanning = raw.uppercase()
        if (scanning.firstOrNull() != 'P') return null

        var inTimeSection = false
        var digits = StringBuilder()
        var totalMinutes = 0
        var sawAnything = false

        for (character in scanning.drop(1)) {
            if (character == 'T') {
                inTimeSection = true
                digits = StringBuilder()
                continue
            }
            if (character.isDigit()) {
                digits.append(character)
                continue
            }
            val value = digits.toString().toIntOrNull()
            if (value == null) {
                digits = StringBuilder()
                continue
            }
            when {
                character == 'D' -> { totalMinutes += value * 24 * 60; sawAnything = true }
                character == 'H' && inTimeSection -> { totalMinutes += value * 60; sawAnything = true }
                character == 'M' && inTimeSection -> { totalMinutes += value; sawAnything = true }
                // A leading "M" outside the time section is months. Nothing
                // sane publishes a recipe in months, and treating it as minutes
                // would be a silent 30-fold error, so it is ignored.
            }
            digits = StringBuilder()
        }
        return if (sawAnything) totalMinutes else null
    }

    /** `author` is a string, a Person object, or a list of either. */
    private fun author(any: Any?): String? {
        if (any == null || any === JSONObject.NULL) return null
        text(any)?.takeIf { it.isNotEmpty() }?.let { return it }
        if (any is JSONObject) return text(any.opt("name"))
        if (any is JSONArray) {
            for (index in 0 until any.length()) {
                author(any.opt(index))?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    /**
     * schema.org states NutritionInformation is per serving, which is the same
     * contract the recipe model already holds — so this maps straight across
     * with no scaling.
     *
     * Values arrive as strings with units attached ("350 calories", "12 g").
     * Only the number is read. Calories are required: a block with no energy
     * value is not worth carrying, because every screen that shows macros leads
     * with calories.
     */
    private fun nutrition(any: Any?): RecipeNutrition? {
        val node = any as? JSONObject ?: return null
        val calories = quantity(node.opt("calories")) ?: return null

        return RecipeNutrition(
            calories = calories,
            proteinG = quantity(node.opt("proteinContent")) ?: 0.0,
            carbsG = quantity(node.opt("carbohydrateContent")) ?: 0.0,
            fatG = quantity(node.opt("fatContent")) ?: 0.0,
            fiberG = quantity(node.opt("fiberContent")) ?: 0.0,
            // The publisher's claim, not a figure resolved against the food
            // database. Every screen that shows it must say so.
            estimated = true,
            saturatedFatG = quantity(node.opt("saturatedFatContent")),
            sugarG = quantity(node.opt("sugarContent")),
            sodiumMg = sodiumMilligrams(node.opt("sodiumContent"))
        )
    }

    /**
     * Sodium is the one field published in two units — "320 mg" on most sites,
     * "0.32 g" on a few European ones. A gram value read as milligrams would be
     * wrong by a thousand, so the unit is checked rather than assumed. A bare
     * number is milligrams. Matches the iOS rule character for character: "mg"
     * anywhere wins, then any "g".
     */
    private fun sodiumMilligrams(any: Any?): Double? {
        val raw = text(any) ?: return null
        val value = firstNumber(raw) ?: return null
        val lowered = raw.lowercase()
        if (lowered.contains("mg")) return value
        if (lowered.contains("g")) return value * 1000
        return value
    }

    private fun quantity(any: Any?): Double? {
        numeric(any)?.let { return it }
        val raw = text(any) ?: return null
        return firstNumber(raw)
    }

    /**
     * A boolean is not a quantity. `org.json` will happily hand back `true` for
     * an `optDouble`-shaped read, so a stray `"recipeYield": true` would
     * otherwise read as 1; it is rejected rather than coerced.
     */
    private fun numeric(any: Any?): Double? = when (any) {
        is Boolean -> null
        is Number -> any.toDouble()
        else -> null
    }

    // MARK: - Small shared helpers

    /**
     * Strings and numbers both reach here, because a publisher may emit
     * `"recipeYield": 4` or `"recipeYield": "4"` for the same dish.
     */
    private fun text(any: Any?): String? {
        if (any is String) return clean(any)
        numeric(any)?.let { return trimmedNumber(it) }
        return null
    }

    /** Whole numbers print without a trailing ".0". */
    private fun trimmedNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun stringList(any: Any?): List<String> {
        if (any == null || any === JSONObject.NULL) return emptyList()
        if (any is JSONArray) {
            val out = mutableListOf<String>()
            for (index in 0 until any.length()) {
                text(any.opt(index))?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            }
            return out
        }
        val single = text(any) ?: return emptyList()
        return if (single.isEmpty()) emptyList() else listOf(single)
    }

    /**
     * First run of digits, with an optional decimal part.
     *
     * Commas are stripped first so "1,200 calories" reads as 1200 rather than
     * stopping at 1. A comma used as a decimal separator would be misread, but
     * JSON-LD nutrition is overwhelmingly written in English-locale numerals
     * and reading "1,5 g" as 15 is the same class of confident-wrong answer
     * [IngredientParser] refuses to produce elsewhere — so it is left alone.
     */
    private fun firstNumber(string: String): Double? {
        val stripped = string.replace(",", "")
        val digits = StringBuilder()
        var sawDot = false
        for (character in stripped) {
            when {
                character.isDigit() -> digits.append(character)
                character == '.' && digits.isNotEmpty() && !sawDot -> {
                    sawDot = true
                    digits.append(character)
                }
                digits.isNotEmpty() -> break
            }
        }
        var out = digits.toString()
        if (out.endsWith(".")) out = out.dropLast(1)
        return out.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    }

    /**
     * Publishers put markup inside JSON-LD string values more often than the
     * spec would suggest — `<p>` around a step, `&amp;` in a title.
     */
    private fun clean(string: String): String =
        stripTags(string)
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()

    private fun stripTags(string: String): String {
        if (!string.contains("<")) return string
        val output = StringBuilder()
        var insideTag = false
        for (character in string) {
            when (character) {
                '<' -> insideTag = true
                '>' -> insideTag = false
                else -> if (!insideTag) output.append(character)
            }
        }
        return output.toString()
    }

    // MARK: - Finding the blocks in a page

    /**
     * Every `<script type="application/ld+json">` body on the page, in order.
     *
     * Matched loosely — a `<script` whose attributes mention the JSON-LD media
     * type — because attribute order and quoting vary and a strict match would
     * miss pages that are otherwise perfectly readable.
     */
    private fun jsonLdBlocks(html: String): List<String> {
        val blocks = mutableListOf<String>()
        var cursor = 0

        while (true) {
            val openStart = html.indexOf("<script", cursor, ignoreCase = true)
            if (openStart < 0) break
            val openEnd = html.indexOf('>', openStart)
            if (openEnd < 0) break
            val closeStart = html.indexOf("</script", openEnd, ignoreCase = true)
            if (closeStart < 0) break

            val attributes = html.substring(openStart, openEnd).lowercase()
            if (attributes.contains("application/ld+json")) {
                val body = html.substring(openEnd + 1, closeStart).trim()
                if (body.isNotEmpty()) blocks.add(body)
            }
            cursor = closeStart + 1
        }
        return blocks
    }
}

/**
 * One recipe read off a page, before any of it has been accepted.
 *
 * A value type, never persisted. Nothing here reaches storage until the app
 * copies it into its own models, which keeps the review step honest: an import
 * is shown next to its source text before it is saved.
 */
data class ImportedRecipe(
    val name: String,
    /**
     * Raw lines, exactly as published. Parsing happens when the app builds its
     * own model, so the unparsed text survives for the reviewer either way.
     */
    val ingredientLines: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    /** Null when the page never said, rather than a guessed 1. */
    val servings: Double? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val author: String? = null,
    /** Per serving, as schema.org defines it. The publisher's claim. */
    val nutritionPerServing: RecipeNutrition? = null,
    /** The source text verbatim, kept for the review screen. */
    val sourceTranscript: String = ""
)

/**
 * Hands a reader-approved caption to the same shape a page import produces, so
 * an app has one save path rather than two.
 *
 * Everything is passed in rather than read from the parse: by this point the
 * reader has edited the boxes, and what they approved wins over what was
 * parsed. [ImportedRecipe.nutritionPerServing] is always null — a caption
 * states no macros this reads.
 */
fun ParsedCaption.imported(
    name: String,
    ingredientLines: List<String>,
    steps: List<String>,
    servings: Double?
): ImportedRecipe = ImportedRecipe(
    name = name,
    ingredientLines = ingredientLines,
    steps = steps,
    servings = servings,
    sourceTranscript = sourceText
)
