package com.dugcanlift.kit

/**
 * One line of a recipe's ingredients.
 *
 * [rawText] is always populated, even when the parse succeeded. It is what the
 * person checks the parse against, and what the shopping list falls back to
 * when [qty] and [unit] could not be resolved.
 */
data class RecipeIngredient(
    val rawText: String,
    val item: String? = null,
    val qty: Double? = null,
    val unit: String? = null,
    /** Resolved mass, when a conversion was possible. Drives macro lookup. */
    val grams: Double? = null,
    val optional: Boolean = false,
    val note: String? = null
) {
    /** The parse when it worked, the raw text when it didn't. */
    val displayText: String
        get() {
            val name = item?.takeIf { it.isNotBlank() } ?: return rawText
            val amount = listOfNotNull(qty?.trimZeros(), unit).joinToString(" ")
            return if (amount.isBlank()) name else "$amount $name"
        }
}
