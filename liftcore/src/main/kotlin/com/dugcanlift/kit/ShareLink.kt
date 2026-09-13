package com.dugcanlift.kit
import org.json.JSONArray
import org.json.JSONObject

data class ShareClient(val id: String, val name: String, val sex: String? = null, val age: Int? = null, val heightIn: Double? = null, val unit: String = "lb", val platform: String? = null)
data class ShareGoal(val calories: Int, val proteinG: Int, val fatG: Int, val carbsG: Int, val fiberG: Int)
data class ShareSet(val weightLb: Double?, val reps: Int?, val rpe: Double?, val durationSec: Double?, val distanceMeters: Double?, val isWarmup: Boolean)
data class ShareExercise(val name: String, val equipment: String, val sets: List<ShareSet>)
data class ShareFood(val name: String, val servings: Double, val calories: Double, val proteinG: Double, val fatG: Double, val carbsG: Double, val fiberG: Double, val meal: Int)
data class ShareDay(val dayOffset: Int, val sessionName: String?, val focus: String?, val bodyweightLb: Double?, val steps: Long?, val exercises: List<ShareExercise>, val foodTotals: List<Double>?, val food: List<ShareFood>?)
data class SharePayload(val client: ShareClient, val goal: ShareGoal?, val startDay: String, val endDay: String, val exportedAtEpochSeconds: Long, val days: List<ShareDay>)
sealed class ShareDecodeResult { data class Success(val payload: SharePayload) : ShareDecodeResult(); object UnsupportedVersion : ShareDecodeResult(); object MalformedPayload : ShareDecodeResult() }

object ShareLinkCodec {
    private const val VERSION = 1
    private const val MAX_FRAGMENT_LENGTH = 200_000

    fun encodeFragment(p: SharePayload): String = "1z" + CompactEncoding.base64Url(CompactEncoding.deflateRaw(buildJson(p).toString().toByteArray()))

    /** The JSON CoachShare.buildPayload built, over the typed payload. Dictionaries by first appearance. */
    fun buildJson(p: SharePayload): JSONObject {
        val exerciseDict = mutableListOf<String>(); val foodDict = mutableListOf<String>()
        val days = JSONArray()
        p.days.forEach { d ->
            val day = JSONObject()
            var any = false
            if (d.exercises.isNotEmpty()) {
                any = true
                d.sessionName?.takeIf { it.isNotBlank() }?.let { day.put("n", it) }
                d.focus?.let { day.put("fo", it) }
                val w = JSONArray()
                d.exercises.forEach { ex ->
                    val idx = indexIn(exerciseDict, "${ex.name.trim()}|${ex.equipment.trim()}")
                    val sets = JSONArray(); ex.sets.forEach { sets.put(setTuple(it)) }
                    w.put(JSONArray().put(idx).put(sets))
                }
                day.put("w", w)
            }
            d.food?.let { list -> if (list.isNotEmpty()) { any = true; val f = JSONArray(); list.forEach { e ->
                f.put(JSONArray().put(indexIn(foodDict, e.name)).put(e.servings).put(e.calories).put(e.proteinG).put(e.fatG).put(e.carbsG).put(e.fiberG).put(e.meal)) }
                day.put("f", f) } }
            d.foodTotals?.let { any = true; day.put("ft", JSONArray(it)) }
            d.steps?.let { any = true; day.put("st", it) }; d.bodyweightLb?.let { any = true; day.put("bw", it) }
            if (any) { day.put("k", d.dayOffset); days.put(day) }
        }
        val c = JSONObject().put("i", p.client.id).put("n", p.client.name.ifBlank { "A LIFT user" }).put("u", p.client.unit)
        p.client.platform?.let { c.put("p", it) }; p.client.sex?.let { c.put("s", it) }; p.client.age?.let { c.put("a", it) }; p.client.heightIn?.let { c.put("h", it) }
        val root = JSONObject().put("v", VERSION).put("c", c).put("r", p.startDay).put("t", p.endDay).put("z", p.exportedAtEpochSeconds).put("x", JSONArray(exerciseDict)).put("d", days)
        p.goal?.let { g -> root.put("g", JSONObject().put("c", g.calories).put("p", g.proteinG).put("f", g.fatG).put("cb", g.carbsG).put("fb", g.fiberG)) }
        if (foodDict.isNotEmpty()) root.put("fd", JSONArray(foodDict))
        return root
    }

    /** [weight, reps, rpe, seconds, metres, flags], trailing nulls AND a trailing 0 flags dropped -- CoachShare's exact rule. */
    private fun setTuple(s: ShareSet): JSONArray {
        val values = mutableListOf<Any?>(s.weightLb, s.reps, s.rpe, s.durationSec, s.distanceMeters, if (s.isWarmup) 1 else 0)
        while (values.isNotEmpty() && (values.last() == null || values.last() == 0)) values.removeAt(values.size - 1)
        val a = JSONArray(); values.forEach { a.put(it ?: JSONObject.NULL) }; return a
    }
    private fun indexIn(dict: MutableList<String>, v: String): Int { val at = dict.indexOf(v); if (at >= 0) return at; dict.add(v); return dict.size - 1 }

    fun decode(fragment: String): ShareDecodeResult {
        if (fragment.length < 2 || fragment.length > MAX_FRAGMENT_LENGTH) return ShareDecodeResult.MalformedPayload
        if (fragment[0] != '1') return ShareDecodeResult.UnsupportedVersion
        val text = try {
            val bytes = CompactEncoding.base64UrlDecode(fragment.substring(2))
            when (fragment[1]) { 'z' -> String(CompactEncoding.inflateRaw(bytes)); 'u' -> String(bytes); else -> return ShareDecodeResult.MalformedPayload }
        } catch (e: Exception) { return ShareDecodeResult.MalformedPayload }
        val json = try { JSONObject(text) } catch (e: Exception) { return ShareDecodeResult.MalformedPayload }
        if (json.optInt("v", -1) != VERSION) return ShareDecodeResult.UnsupportedVersion
        return try { ShareDecodeResult.Success(parse(json)) } catch (e: Exception) { ShareDecodeResult.MalformedPayload }
    }

    private fun parse(j: JSONObject): SharePayload {
        val c = j.getJSONObject("c")
        val client = ShareClient(c.getString("i"), c.optString("n", "Unnamed client"), c.optStringOrNull("s"), c.optIntOrNull("a"), c.optDoubleOrNull("h"),
            if (c.optString("u") == "kg") "kg" else "lb", c.optStringOrNull("p"))
        val goal = j.optJSONObject("g")?.let { ShareGoal(it.optInt("c"), it.optInt("p"), it.optInt("f"), it.optInt("cb"), it.optInt("fb")) }
        val x = j.optJSONArray("x")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        val fd = j.optJSONArray("fd")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        val days = j.optJSONArray("d")?.let { a -> List(a.length()) { parseDay(a.getJSONObject(it), x, fd) } } ?: emptyList()
        return SharePayload(client, goal, j.getString("r"), j.optString("t", j.getString("r")), j.optLong("z", 0), days)
    }

    private fun parseDay(d: JSONObject, x: List<String>, fd: List<String>): ShareDay {
        val exercises = d.optJSONArray("w")?.let { w -> (0 until w.length()).mapNotNull { i ->
            val pair = w.getJSONArray(i); val idx = pair.optInt(0, -1)
            if (idx !in x.indices) return@mapNotNull null   // out-of-range index: drop the entry, never trap
            val (name, equipment) = x[idx].split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val sets = pair.optJSONArray(1)?.let { s -> (0 until s.length()).map { parseSet(s.getJSONArray(it)) } } ?: emptyList()
            ShareExercise(name, equipment, sets) } } ?: emptyList()
        val food = d.optJSONArray("f")?.let { f -> (0 until f.length()).mapNotNull { i ->
            val t = f.getJSONArray(i); if (t.length() < 8) return@mapNotNull null
            val idx = t.optInt(0, -1); if (idx !in fd.indices) return@mapNotNull null
            ShareFood(fd[idx], t.optDouble(1, 1.0), t.optDouble(2), t.optDouble(3), t.optDouble(4), t.optDouble(5), t.optDouble(6), t.optInt(7)) } }
        val ft = d.optJSONArray("ft")?.takeIf { it.length() == 5 }?.let { a -> List(5) { a.optDouble(it) } }
        return ShareDay(d.optInt("k", 0), d.optStringOrNull("n"), d.optStringOrNull("fo"), d.optDoubleOrNull("bw"), d.optLongOrNull("st"), exercises, ft, food)
    }

    /** Short arrays mean the missing trailing fields are null (or 0 flags); never "malformed". */
    private fun parseSet(a: JSONArray): ShareSet {
        fun dbl(i: Int) = if (i < a.length() && !a.isNull(i)) a.optDouble(i) else null
        fun int(i: Int) = if (i < a.length() && !a.isNull(i)) a.optInt(i) else null
        return ShareSet(dbl(0), int(1), dbl(2), dbl(3), dbl(4), ((int(5) ?: 0) and 1) == 1)
    }

    private fun JSONObject.optStringOrNull(k: String) = if (isNull(k)) null else optString(k, "").takeIf { it.isNotEmpty() }
    private fun JSONObject.optIntOrNull(k: String) = if (has(k) && !isNull(k)) optInt(k) else null
    private fun JSONObject.optLongOrNull(k: String) = if (has(k) && !isNull(k)) optLong(k) else null
    private fun JSONObject.optDoubleOrNull(k: String) = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
}
