package com.dugcanlift.kit

/**
 * Macros for one serving. Deliberately nullable at the [Recipe] level: an
 * estimate of the macros of a hand-written recipe is a guess, and `null` says
 * so where zeros would quietly enter someone's daily total as fact.
 *
 * Doubles here, not Int as in [FoodEntry]. The wire format is unrounded, and
 * rounding once at log time beats rounding at every scale.
 */
data class RecipeNutrition(
    val calories: Double = 0.0,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val fiberG: Double = 0.0,
    /** True when derived by an LLM rather than a food database. Show it. */
    val estimated: Boolean = false
) {
    fun scaled(factor: Double) = copy(
        calories = calories * factor,
        proteinG = proteinG * factor,
        carbsG = carbsG * factor,
        fatG = fatG * factor,
        fiberG = fiberG * factor
    )
}
