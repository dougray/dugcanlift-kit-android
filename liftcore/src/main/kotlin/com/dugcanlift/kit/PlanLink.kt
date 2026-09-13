package com.dugcanlift.kit

import org.json.JSONArray
import org.json.JSONObject

data class PlanSet(
    val weightLb: Double? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    val durationSec: Int? = null,
    val distanceMeters: Double? = null
)

data class PlanWorkoutExercise(
    val name: String,
    val equipment: String = "",
    val note: String = "",
    val sets: List<PlanSet> = emptyList()
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
    val steps: List<String> = emptyList()
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
            val nutrition = if (u != null && u.length() >= 5) RecipeNutrition(
                calories = u.optDouble(0, 0.0),
                proteinG = u.optDouble(1, 0.0),
                carbsG = u.optDouble(2, 0.0),
                fatG = u.optDouble(3, 0.0),
                fiberG = u.optDouble(4, 0.0)
            ) else null
            PlanRecipe(
                name = o.optString("n", ""),
                servings = o.optDouble("s", 1.0),
                nutritionPerServing = nutrition,
                ingredients = o.optJSONArray("i").mapStrings(),
                steps = o.optJSONArray("t").mapStrings()
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
                    sets = sets
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
     * `[weightLb, reps, rpe, durationSec, distanceMeters]`, trailing nulls trimmed, any prefix may be
     * null. Lenient: a wrong-typed entry reads as null rather than throwing and losing the whole set.
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
            distanceMeters = d(4)
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
