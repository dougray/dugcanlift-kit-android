package com.dugcanlift.kit

import org.json.JSONArray
import org.json.JSONObject

/**
 * Saturated fat, sugar and sodium: tracked and shown, never targeted -- there is
 * no goal for any of them. Each is null when it was not recorded, which is not
 * the same as zero: a total built from zeros nobody entered reads as a clean
 * day that may not have been one.
 *
 * Per serving wherever it appears on a food or a recipe: a share link's `fe`
 * entry, a plan recipe's `ux`, and [RecipeNutrition]'s fields of the same names.
 */
data class NutrientDetails(val saturatedFatG: Double? = null, val sugarG: Double? = null, val sodiumMg: Double? = null) {
    /** True when none of the three is known. */
    val isEmpty: Boolean get() = saturatedFatG == null && sugarG == null && sodiumMg == null
}

/**
 * A share-link day's `fx`: `[saturatedFatG, sugarG, sodiumMg, foods, withSaturatedFat, withSugar, withSodium]`.
 *
 * Each total covers only the foods that recorded it, multiplied by servings as `ft` is; `foods` is every
 * food logged that day and the `with*` counts say how many of them each total covers -- so a partial total
 * reads as a floor, not a day. A total with no food behind it is null, never 0. Build one with [ShareNutrients.dayTotals].
 */
data class ShareNutrientTotals(
    val saturatedFatG: Double?, val sugarG: Double?, val sodiumMg: Double?,
    val foods: Int, val withSaturatedFat: Int, val withSugar: Int, val withSodium: Int
)

/**
 * Builds the SHARE-FORMAT "Saturated fat, sugar and sodium" parts from a day's foods.
 *
 * Rounding is half-up (`Math.round`), not Kotlin's half-even `round`: grams to one decimal, sodium to whole
 * milligrams. Every total is summed unrounded and rounded once at the end, so a day of many small foods does
 * not accumulate rounding error.
 */
object ShareNutrients {

    /**
     * `fx` for a day: [items] is every food logged that day as (servings, per-serving details), including
     * foods that recorded none of the three, because they still count toward `foods`.
     * Null when no food recorded any of the three -- the day then sends no `fx` at all.
     */
    fun dayTotals(items: List<Pair<Double, NutrientDetails?>>): ShareNutrientTotals? {
        var satFat = 0.0; var sugar = 0.0; var sodium = 0.0
        var withSatFat = 0; var withSugar = 0; var withSodium = 0
        for ((servings, raw) in items) {
            val d = raw?.let(::finite) ?: continue
            d.saturatedFatG?.let { satFat += it * servings; withSatFat++ }
            d.sugarG?.let { sugar += it * servings; withSugar++ }
            d.sodiumMg?.let { sodium += it * servings; withSodium++ }
        }
        if (withSatFat == 0 && withSugar == 0 && withSodium == 0) return null
        return ShareNutrientTotals(
            if (withSatFat > 0) roundGrams(satFat) else null,
            if (withSugar > 0) roundGrams(sugar) else null,
            if (withSodium > 0) roundMilligrams(sodium) else null,
            items.size, withSatFat, withSugar, withSodium
        )
    }

    /** `fx` for an itemized day, reading each [ShareFood.servings] and [ShareFood.details]. */
    @JvmName("dayTotalsOfFoods")
    fun dayTotals(foods: List<ShareFood>): ShareNutrientTotals? = dayTotals(foods.map { it.servings to it.details })

    /**
     * One `fe` entry: `[saturatedFatG, sugarG, sodiumMg]` per serving, rounded, trailing nulls trimmed --
     * so `[null, 12]` is sugar only. Null when the food recorded none of the three. Only trailing nulls go:
     * a leading null holds its slot, or sugar would slide into saturated fat.
     */
    fun itemRow(details: NutrientDetails?): List<Double?>? = NutrientDetailsWire.row(details)

    /** Grams to one decimal, half-up. */
    fun roundGrams(value: Double): Double = Math.round(value * 10) / 10.0

    /** Whole milligrams, half-up. */
    fun roundMilligrams(value: Double): Double = Math.round(value).toDouble()

    /** A non-finite value is as good as unrecorded. */
    internal fun finite(d: NutrientDetails) = NutrientDetails(
        d.saturatedFatG?.takeIf { it.isFinite() }, d.sugarG?.takeIf { it.isFinite() }, d.sodiumMg?.takeIf { it.isFinite() })
}

/** The `[saturatedFatG, sugarG, sodiumMg]` tuple `fe` entries and a plan recipe's `ux` share. */
internal object NutrientDetailsWire {

    fun row(details: NutrientDetails?): List<Double?>? {
        val d = details?.let(ShareNutrients::finite)?.takeIf { !it.isEmpty } ?: return null
        val values = mutableListOf(d.saturatedFatG?.let(ShareNutrients::roundGrams), d.sugarG?.let(ShareNutrients::roundGrams), d.sodiumMg?.let(ShareNutrients::roundMilligrams))
        while (values.isNotEmpty() && values.last() == null) values.removeAt(values.size - 1)
        return values
    }

    fun tuple(row: List<Double?>): JSONArray = JSONArray().also { a -> row.forEach { a.put(it ?: JSONObject.NULL) } }

    /**
     * Short tuples mean the missing trailing slots are null; slots past the third are ignored, so a later
     * addition does not break this reader. A slot holding anything but a finite number or null makes the
     * whole entry null -- a guessed-at sodium figure is worse than none. All-null is null.
     */
    fun parse(t: JSONArray): NutrientDetails? {
        val values = List(3) { i ->
            if (i >= t.length() || t.isNull(i)) null
            else ((t.opt(i) as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return null)
        }
        return NutrientDetails(values[0], values[1], values[2]).takeIf { !it.isEmpty }
    }
}
