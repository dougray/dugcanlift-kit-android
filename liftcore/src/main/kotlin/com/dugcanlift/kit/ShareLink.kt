package com.dugcanlift.kit
import org.json.JSONArray
import org.json.JSONObject

data class ShareClient(val id: String, val name: String, val sex: String? = null, val age: Int? = null, val heightIn: Double? = null, val unit: String = "lb", val platform: String? = null)
data class ShareGoal(val calories: Int, val proteinG: Int, val fatG: Int, val carbsG: Int, val fiberG: Int)
/**
 * Which limb a set was performed with. Null is "both", which is what every set
 * written before per-limb tracking means, so absence never has to be guessed at.
 *
 * [wire] is the value of the set tuple's flags bits 1-2 (SHARE-FORMAT): 0 both,
 * 1 left, 2 right, beside bit 0's warmup flag.
 */
enum class ShareSide(val wire: Int) { LEFT(1), RIGHT(2) }

/** [side] is optional and trailing so every call site written before it still compiles, and null still means both. */
data class ShareSet(val weightLb: Double?, val reps: Int?, val rpe: Double?, val durationSec: Double?, val distanceMeters: Double?, val isWarmup: Boolean,
    val side: ShareSide? = null)
data class ShareExercise(val name: String, val equipment: String, val sets: List<ShareSet>)
/** Macros are per serving, as the wire's `f` carries them. [details] is this food's `fe` entry, also per serving; null when it recorded none of the three. */
data class ShareFood(val name: String, val servings: Double, val calories: Double, val proteinG: Double, val fatG: Double, val carbsG: Double, val fiberG: Double, val meal: Int,
    val details: NutrientDetails? = null)
/** One `o` tuple: a finished run, walk or hike. `type` is 0 run, 1 walk, 2 hike; every number is whole, 0 when nothing was measured. */
data class ShareOutdoor(val type: Int, val durationSec: Long, val distanceMeters: Long, val climbMeters: Long)
/** One `ob` entry, all-time. A null best is "nothing to show", never zero. */
data class ShareOutdoorBest(val type: Int, val count: Int, val farthestMeters: Long?, val longestSec: Long?, val fastestSecPerKm: Long?)
/** `lr`: the four numbers are the whole activity; the polyline is trimmed and thinned (see [OutdoorShare.lastRoute]). */
data class ShareLastRoute(val type: Int, val startedAtEpochSec: Long, val durationSec: Long, val distanceMeters: Long, val climbMeters: Long, val polyline: String)
data class ShareDay(val dayOffset: Int, val sessionName: String?, val focus: String?, val bodyweightLb: Double?, val steps: Long?, val exercises: List<ShareExercise>, val foodTotals: List<Double>?, val food: List<ShareFood>?, val outdoor: List<ShareOutdoor>? = null,
    val nutrientTotals: ShareNutrientTotals? = null)
data class SharePayload(val client: ShareClient, val goal: ShareGoal?, val startDay: String, val endDay: String, val exportedAtEpochSeconds: Long, val days: List<ShareDay>,
    val outdoorBests: List<ShareOutdoorBest>? = null, val lastRoute: ShareLastRoute? = null)
sealed class ShareDecodeResult { data class Success(val payload: SharePayload) : ShareDecodeResult(); object UnsupportedVersion : ShareDecodeResult(); object MalformedPayload : ShareDecodeResult() }

object ShareLinkCodec {
    private const val VERSION = 1
    private const val MAX_FRAGMENT_LENGTH = 200_000

    fun encodeFragment(p: SharePayload): String = "1z" + CompactEncoding.base64Url(CompactEncoding.deflateRaw(buildJson(p).toString().toByteArray()))

    /**
     * The JSON CoachShare.buildPayload built, over the typed payload. Dictionaries by first appearance.
     *
     * Key placement: a key added later is written next to the key it belongs with rather than at the end --
     * `fe` straight after `f`, `fx` straight after `ft`, `o` after the food. Order carries no meaning to a
     * decoder; it keeps a pasted payload readable against SHARE-FORMAT.md.
     */
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
                day.put("f", f)
                // `fe` only ever rides alongside `f`, one entry per `f` entry, and is left out when no food recorded any of the three.
                val rows = list.map { NutrientDetailsWire.row(it.details) }
                if (rows.any { it != null }) { val fe = JSONArray(); rows.forEach { r -> fe.put(r?.let(NutrientDetailsWire::tuple) ?: JSONObject.NULL) }; day.put("fe", fe) }
            } }
            d.foodTotals?.let { any = true; day.put("ft", JSONArray(it)) }
            d.nutrientTotals?.let { t -> any = true; day.put("fx", JSONArray().put(t.saturatedFatG ?: JSONObject.NULL).put(t.sugarG ?: JSONObject.NULL).put(t.sodiumMg ?: JSONObject.NULL)
                .put(t.foods).put(t.withSaturatedFat).put(t.withSugar).put(t.withSodium)) }
            // A day holding only an outdoor activity is still a day.
            d.outdoor?.let { list -> if (list.isNotEmpty()) { any = true; val o = JSONArray()
                list.forEach { a -> o.put(JSONArray().put(a.type).put(a.durationSec).put(a.distanceMeters).put(a.climbMeters)) }
                day.put("o", o) } }
            d.steps?.let { any = true; day.put("st", it) }; d.bodyweightLb?.let { any = true; day.put("bw", it) }
            if (any) { day.put("k", d.dayOffset); days.put(day) }
        }
        val c = JSONObject().put("i", p.client.id).put("n", p.client.name.ifBlank { "A LIFT user" }).put("u", p.client.unit)
        p.client.platform?.let { c.put("p", it) }; p.client.sex?.let { c.put("s", it) }; p.client.age?.let { c.put("a", it) }; p.client.heightIn?.let { c.put("h", it) }
        val root = JSONObject().put("v", VERSION).put("c", c).put("r", p.startDay).put("t", p.endDay).put("z", p.exportedAtEpochSeconds).put("x", JSONArray(exerciseDict)).put("d", days)
        p.goal?.let { g -> root.put("g", JSONObject().put("c", g.calories).put("p", g.proteinG).put("f", g.fatG).put("cb", g.carbsG).put("fb", g.fiberG)) }
        if (foodDict.isNotEmpty()) root.put("fd", JSONArray(foodDict))
        p.outdoorBests?.let { list -> if (list.isNotEmpty()) { val ob = JSONArray()
            list.forEach { b -> ob.put(JSONArray().put(b.type).put(b.count).put(b.farthestMeters ?: JSONObject.NULL).put(b.longestSec ?: JSONObject.NULL).put(b.fastestSecPerKm ?: JSONObject.NULL)) }
            root.put("ob", ob) } }
        p.lastRoute?.let { r -> root.put("lr", JSONArray().put(r.type).put(r.startedAtEpochSec).put(r.durationSec).put(r.distanceMeters).put(r.climbMeters).put(r.polyline)) }
        return root
    }

    /**
     * [weight, reps, rpe, seconds, metres, flags], trailing nulls AND a trailing 0 flags dropped -- CoachShare's exact rule.
     *
     * flags is bit 0 warmup, bits 1-2 the side. A both-sided working set is still
     * 0 and still trimmed away, so a link carrying no per-limb sets is byte for
     * byte the link this encoder wrote before sides existed.
     */
    private fun setTuple(s: ShareSet): JSONArray {
        val values = mutableListOf<Any?>(s.weightLb, s.reps, s.rpe, s.durationSec, s.distanceMeters, setFlags(s))
        while (values.isNotEmpty() && (values.last() == null || values.last() == 0)) values.removeAt(values.size - 1)
        val a = JSONArray(); values.forEach { a.put(it ?: JSONObject.NULL) }; return a
    }
    internal fun setFlags(s: ShareSet): Int = (if (s.isWarmup) 1 else 0) or ((s.side?.wire ?: 0) shl 1)

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
        val ob = j.optJSONArray("ob")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it)?.let(::parseBest) } }?.takeIf { it.isNotEmpty() }
        return SharePayload(client, goal, j.getString("r"), j.optString("t", j.getString("r")), j.optLong("z", 0), days, ob, j.optJSONArray("lr")?.let(::parseLastRoute))
    }

    private fun parseDay(d: JSONObject, x: List<String>, fd: List<String>): ShareDay {
        val exercises = d.optJSONArray("w")?.let { w -> (0 until w.length()).mapNotNull { i ->
            val pair = w.getJSONArray(i); val idx = pair.optInt(0, -1)
            if (idx !in x.indices) return@mapNotNull null   // out-of-range index: drop the entry, never trap
            val (name, equipment) = x[idx].split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val sets = pair.optJSONArray(1)?.let { s -> (0 until s.length()).map { parseSet(s.getJSONArray(it)) } } ?: emptyList()
            ShareExercise(name, equipment, sets) } } ?: emptyList()
        // `fe` is aligned with `f` by position, so it is matched against the raw `f` index before any bad `f` entry is dropped.
        // A length that disagrees with `f` could pin sodium on the wrong food, so the whole `fe` is ignored instead.
        val fRaw = d.optJSONArray("f")
        val fe = d.optJSONArray("fe")?.takeIf { fRaw != null && it.length() == fRaw.length() }
        val food = fRaw?.let { f -> (0 until f.length()).mapNotNull { i ->
            val t = f.getJSONArray(i); if (t.length() < 8) return@mapNotNull null
            val idx = t.optInt(0, -1); if (idx !in fd.indices) return@mapNotNull null
            ShareFood(fd[idx], t.optDouble(1, 1.0), t.optDouble(2), t.optDouble(3), t.optDouble(4), t.optDouble(5), t.optDouble(6), t.optInt(7),
                fe?.optJSONArray(i)?.let(NutrientDetailsWire::parse)) } }
        val ft = d.optJSONArray("ft")?.takeIf { it.length() == 5 }?.let { a -> List(5) { a.optDouble(it) } }
        val o = d.optJSONArray("o")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it)?.let(::parseOutdoor) } }?.takeIf { it.isNotEmpty() }
        val fx = d.optJSONArray("fx")?.let(::parseNutrientTotals)
        return ShareDay(d.optInt("k", 0), d.optStringOrNull("n"), d.optStringOrNull("fo"), d.optDoubleOrNull("bw"), d.optLongOrNull("st"), exercises, ft, food, o, fx)
    }

    // Outdoor parts arrived without a version bump, so a malformed one is dropped on its own -- never the whole payload.
    private fun parseOutdoor(t: JSONArray): ShareOutdoor? {
        val n = List(4) { t.wholeOrNull(it) ?: return null }
        return ShareOutdoor(n[0].toInt(), n[1], n[2], n[3])
    }
    private fun parseBest(t: JSONArray): ShareOutdoorBest? {
        if (t.length() < 5) return null
        val type = t.wholeOrNull(0) ?: return null; val count = t.wholeOrNull(1) ?: return null
        return ShareOutdoorBest(type.toInt(), count.toInt(), t.wholeOrNull(2), t.wholeOrNull(3), t.wholeOrNull(4))
    }
    private fun parseLastRoute(t: JSONArray): ShareLastRoute? {
        val n = List(5) { t.wholeOrNull(it) ?: return null }
        val polyline = t.opt(5) as? String ?: return null
        return ShareLastRoute(n[0].toInt(), n[1], n[2], n[3], n[4], polyline)
    }
    // `fx` arrived the same way: a malformed one reads as null and the day keeps its food.
    private fun parseNutrientTotals(t: JSONArray): ShareNutrientTotals? {
        if (t.length() < 7) return null
        val totals = List(3) { i -> if (t.isNull(i)) null else ((t.opt(i) as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return null) }
        val counts = List(4) { i -> t.wholeOrNull(3 + i)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: return null }
        return ShareNutrientTotals(totals[0], totals[1], totals[2], counts[0], counts[1], counts[2], counts[3])
    }
    /** A finite number at `i`, rounded; null for a missing, null or non-numeric slot. */
    private fun JSONArray.wholeOrNull(i: Int): Long? = (opt(i) as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { Math.round(it) }

    /** Short arrays mean the missing trailing fields are null (or 0 flags); never "malformed". */
    private fun parseSet(a: JSONArray): ShareSet {
        fun dbl(i: Int) = if (i < a.length() && !a.isNull(i)) a.optDouble(i) else null
        fun int(i: Int) = if (i < a.length() && !a.isNull(i)) a.optInt(i) else null
        val flags = int(5) ?: 0
        return ShareSet(dbl(0), int(1), dbl(2), dbl(3), dbl(4), (flags and 1) == 1, sideOf(flags))
    }

    /**
     * Bits 1-2 of a set's flags. 0 is both, and so is 3 -- an unassigned pair of
     * bits a newer writer might use for something else must read as "both", not
     * as a side this decoder invented.
     */
    internal fun sideOf(flags: Int): ShareSide? = when ((flags shr 1) and 3) {
        1 -> ShareSide.LEFT
        2 -> ShareSide.RIGHT
        else -> null
    }

    private fun JSONObject.optStringOrNull(k: String) = if (isNull(k)) null else optString(k, "").takeIf { it.isNotEmpty() }
    private fun JSONObject.optIntOrNull(k: String) = if (has(k) && !isNull(k)) optInt(k) else null
    private fun JSONObject.optLongOrNull(k: String) = if (has(k) && !isNull(k)) optLong(k) else null
    private fun JSONObject.optDoubleOrNull(k: String) = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
}
