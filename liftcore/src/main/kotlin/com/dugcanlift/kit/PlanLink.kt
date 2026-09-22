package com.dugcanlift.kit

import org.json.JSONArray
import org.json.JSONObject

/**
 * One prescribed set: `[weightLb, reps, rpe, durationSec, distanceMeters, flags]`.
 *
 * [side] is PLAN-FORMAT "Sides" rule 2 -- a set the coach put on one side only, done on that side
 * once. It is the sixth tuple position, SHARE-FORMAT's flags byte, read the way [ShareLinkCodec]
 * reads a logged set's: bits 1-2 masked, never compared, `3` in them is both. Optional and
 * trailing, so every call site written before it compiles, and null means both -- what every set
 * written before sides means.
 */
data class PlanSet(
    val weightLb: Double? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    val durationSec: Int? = null,
    val distanceMeters: Double? = null,
    val side: ShareSide? = null
)

/**
 * [eachSide] is PLAN-FORMAT "Sides" rule 1, the exercise's `b: 1`: every prescribed set is done on
 * both sides, so three sets each side stay three [sets]. Anything but `1` -- absent, `0`, junk --
 * is false, today's meaning.
 */
data class PlanWorkoutExercise(
    val name: String,
    val equipment: String = "",
    val note: String = "",
    val sets: List<PlanSet> = emptyList(),
    val eachSide: Boolean = false
)

data class PlanWorkout(
    val name: String,
    val exercises: List<PlanWorkoutExercise> = emptyList()
)

data class PlanRecipe(
    val name: String,
    val servings: Double,
    val nutritionPerServing: RecipeNutrition? = null,
    val ingredients: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    /**
     * The wire's `ux`, per serving; null when the plan sent none. Separate from [nutritionPerServing]
     * because `ux` travels without `u` when the coach entered no macros. When both arrived,
     * [nutritionPerServing] carries the same three values too.
     */
    val nutrientDetailsPerServing: NutrientDetails? = null
)

data class PlanMeal(
    val date: String,
    val mealSlot: Int,
    val recipeIndex: Int,
    val servings: Double
)

data class PlanSession(
    val date: String,
    val workoutIndex: Int
)

data class PlanPayload(
    val coachName: String,
    val recipes: List<PlanRecipe>,
    val meals: List<PlanMeal>,
    val workouts: List<PlanWorkout>,
    val sessions: List<PlanSession>,
    val rawJson: String
)

sealed class PlanDecodeResult {
    data class Success(val payload: PlanPayload) : PlanDecodeResult()
    object NotAddressedToYou : PlanDecodeResult()
    object UnsupportedVersion : PlanDecodeResult()
    object MalformedPayload : PlanDecodeResult()
}

object PlanLinkCodec {

    private const val SUPPORTED_VERSION = 1

    /** Generous margin over PLAN-FORMAT.md's 16,000-char link ceiling, accounting for encoding overhead. */
    private const val MAX_FRAGMENT_LENGTH = 20_000

    /** Far more than any legitimate plan could need — guards against a DEFLATE zip-bomb shaped link. */
    private const val MAX_INFLATED_BYTES = 256 * 1024

    fun decode(fragment: String, expectedLifterId: String): PlanDecodeResult {
        if (fragment.length < 2) return PlanDecodeResult.MalformedPayload
        if (fragment.length > MAX_FRAGMENT_LENGTH) return PlanDecodeResult.MalformedPayload
        val version = fragment[0]
        val codec = fragment[1]
        if (version != '1') return PlanDecodeResult.UnsupportedVersion
        val encoded = fragment.substring(2)

        val jsonText = try {
            val bytes = CompactEncoding.base64UrlDecode(encoded)
            when (codec) {
                'z' -> String(CompactEncoding.inflateRaw(bytes), Charsets.UTF_8)
                'u' -> String(bytes, Charsets.UTF_8)
                else -> return PlanDecodeResult.MalformedPayload
            }
        } catch (e: Exception) {
            return PlanDecodeResult.MalformedPayload
        }

        val json = try { JSONObject(jsonText) } catch (e: Exception) {
            return PlanDecodeResult.MalformedPayload
        }

        if (json.optInt("v", -1) != SUPPORTED_VERSION) return PlanDecodeResult.UnsupportedVersion
        if (json.optString("t") != "plan") return PlanDecodeResult.MalformedPayload
        val lifterId = json.optString("l")
        if (lifterId != expectedLifterId) return PlanDecodeResult.NotAddressedToYou

        return try {
            PlanDecodeResult.Success(parsePayload(json, jsonText))
        } catch (e: Exception) {
            PlanDecodeResult.MalformedPayload
        }
    }

    /**
     * Lenient by design: every field falls back to a default and every array entry is read with
     * `opt*` accessors, so one malformed recipe/meal/workout/exercise/session/set inside an
     * otherwise-valid payload is skipped rather than aborting the whole import. Additive wire-format
     * keys deliberately don't bump the version number (see PLAN-FORMAT.md) specifically so this
     * client keeps what it understands instead of rejecting the entire plan over one bad field.
     */
    private fun parsePayload(json: JSONObject, rawJson: String): PlanPayload {
        val recipes = json.optJSONArray("r").mapObjects { o ->
            val u = o.optJSONArray("u")
            // `ux` is its own key, never more positions in `u`; a malformed one reads as null and the recipe stays.
            val ux = o.optJSONArray("ux")?.let(NutrientDetailsWire::parse)
            val nutrition = if (u != null && u.length() >= 5) RecipeNutrition(
                calories = u.optDouble(0, 0.0),
                proteinG = u.optDouble(1, 0.0),
                carbsG = u.optDouble(2, 0.0),
                fatG = u.optDouble(3, 0.0),
                fiberG = u.optDouble(4, 0.0),
                saturatedFatG = ux?.saturatedFatG,
                sugarG = ux?.sugarG,
                sodiumMg = ux?.sodiumMg
            ) else null
            PlanRecipe(
                name = o.optString("n", ""),
                servings = o.optDouble("s", 1.0),
                nutritionPerServing = nutrition,
                ingredients = o.optJSONArray("i").mapStrings(),
                steps = o.optJSONArray("t").mapStrings(),
                nutrientDetailsPerServing = ux
            )
        }

        val meals = json.optJSONArray("m").mapObjects { o ->
            PlanMeal(
                date = o.optString("d", ""),
                mealSlot = o.optInt("s", 2),
                recipeIndex = o.optInt("x", -1),
                servings = o.optDouble("q", 1.0)
            )
        }

        val workouts = json.optJSONArray("w").mapObjects { o ->
            val exercises = o.optJSONArray("e").mapObjects { eo ->
                val sets = eo.optJSONArray("s")?.let { arr ->
                    (0 until arr.length()).mapNotNull { k -> arr.optJSONArray(k) }.map(::parseSet)
                } ?: emptyList()
                PlanWorkoutExercise(
                    name = eo.optString("n", ""),
                    equipment = eo.optString("q", ""),
                    note = eo.optString("c", ""),
                    sets = sets,
                    eachSide = eo.optDouble("b", 0.0) == 1.0
                )
            }
            PlanWorkout(name = o.optString("n", ""), exercises = exercises)
        }

        val sessions = json.optJSONArray("k").mapObjects { o ->
            PlanSession(date = o.optString("d", ""), workoutIndex = o.optInt("x", -1))
        }

        return PlanPayload(
            coachName = json.optString("n", "Your coach"),
            recipes = recipes,
            meals = meals,
            workouts = workouts,
            sessions = sessions,
            rawJson = rawJson
        )
    }

    /**
     * `[weightLb, reps, rpe, durationSec, distanceMeters, flags]`, trailing nulls trimmed, any prefix
     * may be null. Lenient: a wrong-typed entry reads as null rather than throwing and losing the
     * whole set, and a missing or junk `flags` is both. Only bits 1-2 of `flags` mean anything here;
     * bit 0 (SHARE-FORMAT's warmup) is ignored, not a reason to drop the side.
     */
    private fun parseSet(array: JSONArray): PlanSet {
        fun d(i: Int): Double? {
            if (i >= array.length() || array.isNull(i)) return null
            val v = array.optDouble(i)
            return if (v.isNaN()) null else v
        }
        fun n(i: Int): Int? = d(i)?.toInt()
        return PlanSet(
            weightLb = d(0),
            reps = n(1),
            rpe = d(2),
            durationSec = n(3),
            distanceMeters = d(4),
            side = d(5)?.toInt()?.let(ShareLinkCodec::sideOf)
        )
    }
}

private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }.map(transform)
}

private fun JSONArray?.mapStrings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it, "") }.filter { it.isNotBlank() }
}
