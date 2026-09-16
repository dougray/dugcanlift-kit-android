package com.dugcanlift.kit

import org.junit.Assert.*
import org.junit.Test

/** SHARE-FORMAT "Saturated fat, sugar and sodium" (`fx`, `fe`) and PLAN-FORMAT's `ux`. */
class NutrientDetailsTest {
    private fun link(json: String) = "1u" + CompactEncoding.base64Url(json.toByteArray())
    private fun share(json: String) = (ShareLinkCodec.decode(link(json)) as ShareDecodeResult.Success).payload
    private fun roundTrip(p: SharePayload) = (ShareLinkCodec.decode(ShareLinkCodec.encodeFragment(p)) as ShareDecodeResult.Success).payload
    private fun plan(json: String) = (PlanLinkCodec.decode(link(json), "l1") as PlanDecodeResult.Success).payload

    private fun food(name: String, servings: Double, details: NutrientDetails?) = ShareFood(name, servings, 100.0, 10.0, 5.0, 8.0, 1.0, 2, details)
    private fun payload(day: ShareDay) = SharePayload(ShareClient("x", "N"), null, "2026-09-10", "2026-09-16", 1, listOf(day))
    private fun foodDay(foods: List<ShareFood>?, totals: ShareNutrientTotals?) =
        ShareDay(0, null, null, null, null, emptyList(), listOf(400.0, 30.0, 10.0, 40.0, 5.0), foods, null, totals)

    // Building fx and fe.

    @Test fun `day totals multiply by servings, cover only known values and count every food`() {
        val t = ShareNutrients.dayTotals(listOf(
            2.0 to NutrientDetails(3.1, 2.0, 540.0),
            1.0 to null,
            0.5 to NutrientDetails(null, 12.0),
            1.0 to NutrientDetails(sodiumMg = 100.0)
        ))
        assertEquals(ShareNutrientTotals(6.2, 10.0, 1180.0, 4, 1, 2, 2), t)
    }
    @Test fun `a total nobody recorded is null, and no details at all is no fx`() {
        assertEquals(ShareNutrientTotals(null, 4.0, null, 2, 0, 1, 0), ShareNutrients.dayTotals(listOf(1.0 to NutrientDetails(sugarG = 4.0), 1.0 to NutrientDetails())))
        assertNull(ShareNutrients.dayTotals(listOf(1.0 to null, 3.0 to NutrientDetails())))
        assertNull(ShareNutrients.dayTotals(emptyList<Pair<Double, NutrientDetails?>>()))
    }
    @Test fun `a recorded zero is a value, not a gap`() {
        assertEquals(ShareNutrientTotals(null, null, 0.0, 1, 0, 0, 1), ShareNutrients.dayTotals(listOf(1.0 to NutrientDetails(sodiumMg = 0.0))))
        assertEquals(listOf(null, null, 0.0), ShareNutrients.itemRow(NutrientDetails(sodiumMg = 0.0)))
    }
    @Test fun `a non-finite value counts as unrecorded`() {
        assertEquals(ShareNutrientTotals(1.0, null, null, 1, 1, 0, 0), ShareNutrients.dayTotals(listOf(1.0 to NutrientDetails(1.0, Double.NaN, Double.POSITIVE_INFINITY))))
        assertNull(ShareNutrients.itemRow(NutrientDetails(Double.NaN)))
    }
    @Test fun `rounding is half-up, grams to one decimal and sodium whole`() {
        assertEquals(0.1, ShareNutrients.roundGrams(0.05), 0.0)          // half-even would give 0.0
        assertEquals(12.3, ShareNutrients.roundGrams(12.25), 0.0)        // half-even would give 12.2
        assertEquals(3.0, ShareNutrients.roundMilligrams(2.5), 0.0)      // half-even would give 2.0
        assertEquals(ShareNutrientTotals(0.1, 1.0, 3.0, 1, 1, 1, 1), ShareNutrients.dayTotals(listOf(0.5 to NutrientDetails(0.1, 1.94, 5.0))))
    }
    @Test fun `totals are summed unrounded and rounded once`() {
        // Three 0.04 g servings round to 0.0 each; the day holds 0.12 g, which is 0.1.
        assertEquals(0.1, ShareNutrients.dayTotals(List(3) { 1.0 to NutrientDetails(0.04) })!!.saturatedFatG!!, 0.0)
    }
    @Test fun `item rows trim trailing nulls only`() {
        assertEquals(listOf(3.1, 2.0, 540.0), ShareNutrients.itemRow(NutrientDetails(3.14, 2.0, 539.6)))
        assertEquals(listOf(null, 12.0), ShareNutrients.itemRow(NutrientDetails(sugarG = 12.0)))
        assertEquals(listOf(1.5), ShareNutrients.itemRow(NutrientDetails(saturatedFatG = 1.5)))
        assertNull(ShareNutrients.itemRow(NutrientDetails()))
        assertNull(ShareNutrients.itemRow(null))
    }
    @Test fun `day totals read an itemized day's foods`() {
        val foods = listOf(food("a", 2.0, NutrientDetails(1.0)), food("b", 1.0, null))
        assertEquals(ShareNutrientTotals(2.0, null, null, 2, 1, 0, 0), ShareNutrients.dayTotals(foods))
    }

    // Share link encode and decode.

    @Test fun `fx and fe are written beside ft and f in the spec's shapes`() {
        val foods = listOf(food("Oats", 1.0, NutrientDetails(3.1, 2.0, 540.0)), food("Apple", 1.0, null), food("Juice", 2.0, NutrientDetails(sugarG = 12.0)))
        val p = payload(foodDay(foods, ShareNutrients.dayTotals(foods)))
        val day = ShareLinkCodec.buildJson(p).getJSONArray("d").getJSONObject(0)
        assertEquals("[[3.1,2,540],null,[null,12]]", day.getJSONArray("fe").toString())
        assertEquals("[3.1,26,540,3,1,2,1]", day.getJSONArray("fx").toString())
        assertEquals(p, roundTrip(p))
    }
    @Test fun `decodes the spec's own example`() {
        val p = share("""{"v":1,"c":{"i":"x","n":"N"},"r":"2026-09-01","t":"2026-09-01","z":1,"fd":["a","b","c","d","e","f"],"d":[{"k":0,
            "f":[[0,1,1,1,1,1,1,0],[1,1,1,1,1,1,1,0],[2,2,1,1,1,1,1,0]],"fe":[[3.1,2,540],null,[null,12]],
            "ft":[1,1,1,1,1],"fx":[21.5,48,2310,6,4,5,6]}]}""")
        val day = p.days[0]
        assertEquals(ShareNutrientTotals(21.5, 48.0, 2310.0, 6, 4, 5, 6), day.nutrientTotals)
        assertEquals(listOf(NutrientDetails(3.1, 2.0, 540.0), null, NutrientDetails(null, 12.0, null)), day.food!!.map { it.details })
    }
    @Test fun `no details writes no fx or fe, and an old payload decodes and re-encodes unchanged`() {
        val text = """{"v":1,"c":{"i":"x","n":"N","u":"lb"},"r":"2026-09-01","t":"2026-09-02","z":1,"x":[],"fd":["Oats"],"d":[{"k":0,"f":[[0,1,389,17,7,66,11,0]],"ft":[389,17,7,66,11]}]}"""
        val p = share(text)
        assertNull(p.days[0].nutrientTotals); assertNull(p.days[0].food!![0].details)
        val day = ShareLinkCodec.buildJson(p).getJSONArray("d").getJSONObject(0)
        assertFalse(day.has("fx")); assertFalse(day.has("fe"))
        assertEquals(p, roundTrip(p))
        // Foods whose details are all empty still send no fe.
        val empty = payload(foodDay(listOf(food("a", 1.0, NutrientDetails())), null))
        assertFalse(ShareLinkCodec.buildJson(empty).getJSONArray("d").getJSONObject(0).has("fe"))
    }
    @Test fun `fx on a day without itemized food is sent and read`() {
        val p = payload(foodDay(null, ShareNutrientTotals(null, 30.0, null, 3, 0, 2, 0)))
        assertEquals("[null,30,null,3,0,2,0]", ShareLinkCodec.buildJson(p).getJSONArray("d").getJSONObject(0).getJSONArray("fx").toString())
        assertEquals(p, roundTrip(p))
    }
    @Test fun `malformed fx and fe read as null without losing the day's food`() {
        for (fx in listOf("\"x\"", "[1,2,3,4,5,6]", "[\"1\",2,3,4,1,1,1]", "[1,2,3,-1,1,1,1]", "[1,2,3,4,1,\"one\",1]", "{}")) {
            val p = share("""{"v":1,"c":{"i":"x","n":"N"},"r":"2026-09-01","z":1,"fd":["a"],"d":[{"k":0,"st":5000,"f":[[0,1,1,1,1,1,1,0]],"ft":[1,1,1,1,1],"fx":$fx}]}""")
            assertNull(fx, p.days[0].nutrientTotals)
            assertEquals(1, p.days[0].food!!.size); assertEquals(5000L, p.days[0].steps)
        }
        val p = share("""{"v":1,"c":{"i":"x","n":"N"},"r":"2026-09-01","z":1,"fd":["a","b","c","d"],"d":[{"k":0,
            "f":[[0,1,1,1,1,1,1,0],[1,1,1,1,1,1,1,0],[2,1,1,1,1,1,1,0],[3,1,1,1,1,1,1,0]],"fe":[["3",1,1],"row",[],[1,true]]}]}""")
        assertEquals(4, p.days[0].food!!.size)
        assertTrue(p.days[0].food!!.all { it.details == null })
    }
    @Test fun `fe whose length disagrees with f is ignored rather than misaligned`() {
        val p = share("""{"v":1,"c":{"i":"x","n":"N"},"r":"2026-09-01","z":1,"fd":["a","b"],"d":[{"k":0,
            "f":[[0,1,1,1,1,1,1,0],[1,1,1,1,1,1,1,0]],"fe":[[1,2,3]]}]}""")
        assertEquals(2, p.days[0].food!!.size)
        assertTrue(p.days[0].food!!.all { it.details == null })
    }
    @Test fun `fe stays aligned with f when an f entry is dropped`() {
        val p = share("""{"v":1,"c":{"i":"x","n":"N"},"r":"2026-09-01","z":1,"fd":["a","b"],"d":[{"k":0,
            "f":[[9,1,1,1,1,1,1,0],[1,1,1,1,1,1,1,0]],"fe":[[5],[null,7]]}]}""")
        assertEquals(listOf(ShareFood("b", 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0, NutrientDetails(sugarG = 7.0))), p.days[0].food)
    }
    @Test fun `a longer fe entry keeps its first three slots`() {
        val p = share("""{"v":1,"c":{"i":"x","n":"N"},"r":"2026-09-01","z":1,"fd":["a"],"d":[{"k":0,"f":[[0,1,1,1,1,1,1,0]],"fe":[[1,2,3,99]]}]}""")
        assertEquals(NutrientDetails(1.0, 2.0, 3.0), p.days[0].food!![0].details)
    }

    // Plan link ux.

    @Test fun `ux is read per serving and carried into the recipe's nutrition`() {
        val r = plan("""{"v":1,"t":"plan","l":"l1","n":"C","r":[{"n":"Chilli","s":4,"u":[438,36,31,19,9],"ux":[6.5,8,720]}]}""").recipes[0]
        assertEquals(NutrientDetails(6.5, 8.0, 720.0), r.nutrientDetailsPerServing)
        assertEquals(NutrientDetails(6.5, 8.0, 720.0), r.nutritionPerServing!!.details)
        assertEquals(438.0, r.nutritionPerServing.calories, 0.0)
    }
    @Test fun `ux without u survives, trimmed tuples read, and absent ux is null`() {
        val p = plan("""{"v":1,"t":"plan","l":"l1","n":"C","r":[{"n":"A","s":1,"ux":[null,12]},{"n":"B","s":1,"u":[1,2,3,4,5]}]}""")
        assertNull(p.recipes[0].nutritionPerServing)
        assertEquals(NutrientDetails(sugarG = 12.0), p.recipes[0].nutrientDetailsPerServing)
        assertNull(p.recipes[1].nutrientDetailsPerServing)
        assertEquals(NutrientDetails(), p.recipes[1].nutritionPerServing!!.details)
    }
    @Test fun `malformed ux reads as null without losing the recipe`() {
        for (ux in listOf("\"x\"", "[\"6\",1]", "[]", "[null,null,null]", "{}")) {
            val r = plan("""{"v":1,"t":"plan","l":"l1","n":"C","r":[{"n":"A","s":2,"u":[1,2,3,4,5],"ux":$ux,"i":["1 egg"]}]}""").recipes.single()
            assertNull(ux, r.nutrientDetailsPerServing)
            assertEquals(listOf("1 egg"), r.ingredients)
            assertEquals(1.0, r.nutritionPerServing!!.calories, 0.0)
        }
    }

    // RecipeNutrition.

    @Test fun `recipe nutrition scales the three and keeps null null`() {
        val n = RecipeNutrition(400.0, 30.0, 40.0, 10.0, 5.0, saturatedFatG = 3.0, sodiumMg = 500.0).scaled(2.0)
        assertEquals(NutrientDetails(6.0, null, 1000.0), n.details)
        assertEquals(RecipeNutrition(1.0, 2.0, 3.0, 4.0, 5.0, true), RecipeNutrition(0.5, 1.0, 1.5, 2.0, 2.5, true).scaled(2.0))
        assertNull(RecipeNutrition().scaled(3.0).sugarG)
    }
    @Test fun `recipe nutrition details round-trip through the ux tuple`() {
        val n = RecipeNutrition(400.0, saturatedFatG = 3.5, sugarG = null, sodiumMg = 610.0)
        val ux = NutrientDetailsWire.tuple(ShareNutrients.itemRow(n.details)!!).toString()
        assertEquals("[3.5,null,610]", ux)
        val r = plan("""{"v":1,"t":"plan","l":"l1","n":"C","r":[{"n":"A","s":1,"u":[400,0,0,0,0],"ux":$ux}]}""").recipes[0]
        assertEquals(n.copy(estimated = false), r.nutritionPerServing)
    }
}
