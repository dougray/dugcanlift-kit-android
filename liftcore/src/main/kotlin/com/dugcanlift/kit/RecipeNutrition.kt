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
    /**
     * Null is "not known", never zero (1.10.0; a plain `Double` defaulting to 0 before). A recipe
     * page, a catalogue entry or a typed form that states no fibre leaves it null, as the iOS kit's
     * `NutritionFacts.fiberG` does, so "no fibre data" never silently becomes "0 g" in a day total.
     * A zero that was stated stays zero. Stays null through [scaled].
     */
    val fiberG: Double? = null,
    /** True when derived by an LLM rather than a food database. Show it. */
    val estimated: Boolean = false,
    /**
     * Tracked and shown, never targeted. Null is "not known", never zero, and stays null through
     * [scaled]. Placed after [estimated] so every existing positional call still compiles.
     */
    val saturatedFatG: Double? = null,
    val sugarG: Double? = null,
    val sodiumMg: Double? = null
) {
    /** The three as one value, the shape `fe` and `ux` carry. */
    val details: NutrientDetails get() = NutrientDetails(saturatedFatG, sugarG, sodiumMg)

    fun scaled(factor: Double) = copy(
        calories = calories * factor,
        proteinG = proteinG * factor,
        carbsG = carbsG * factor,
        fatG = fatG * factor,
        fiberG = fiberG?.let { it * factor },
        saturatedFatG = saturatedFatG?.let { it * factor },
        sugarG = sugarG?.let { it * factor },
        sodiumMg = sodiumMg?.let { it * factor }
    )
}
