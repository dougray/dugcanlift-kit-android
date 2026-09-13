package com.dugcanlift.kit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ShareLinkCodecTest {
    private fun fixture() = javaClass.getResourceAsStream("/fixtures/share-link-android.txt")!!.bufferedReader().readText().trim()
    private val payload = SharePayload(
        client = ShareClient("a1b2c3d4", "Doug", platform = "and"), goal = ShareGoal(2400, 190, 70, 220, 34),
        startDay = "2026-08-17", endDay = "2026-09-13", exportedAtEpochSeconds = 1757800000,
        days = listOf(ShareDay(2, "Lower A", "POWERLIFTING", 209.4, 8421,
            exercises = listOf(ShareExercise("Back Squat", "Barbell", listOf(ShareSet(135.0, 5, null, null, null, true), ShareSet(225.0, 5, 8.0, null, null, false), ShareSet(245.0, 3, 9.0, null, null, false)))),
            foodTotals = null, food = listOf(ShareFood("Oats", 2.0, 190.0, 6.5, 3.3, 34.0, 5.0, 0)))))

    @Test fun `round trips through encode and decode`() {
        val back = ShareLinkCodec.decode(ShareLinkCodec.encodeFragment(payload)) as ShareDecodeResult.Success
        assertEquals(payload, back.payload)
    }
    @Test fun `sets are trimmed of trailing nulls and a trailing zero flags`() {
        val j = ShareLinkCodec.buildJson(payload)
        val sets = j.getJSONArray("d").getJSONObject(0).getJSONArray("w").getJSONArray(0).getJSONArray(1)
        assertEquals("[135,5,null,null,null,1]", sets.getJSONArray(0).toString())
        assertEquals("[225,5,8]", sets.getJSONArray(1).toString())
    }
    @Test fun `a short set array decodes with nulls not zeros and flags absent means not warmup`() {
        val j = JSONObject("""{"v":1,"c":{"i":"x","n":"N"},"r":"2026-09-01","t":"2026-09-01","z":1,"x":["Row|Cable"],"d":[{"k":0,"w":[[0,[[null,5]]]]}]}""")
        val p = (ShareLinkCodec.decode("1u" + CompactEncoding.base64Url(j.toString().toByteArray())) as ShareDecodeResult.Success).payload
        val set = p.days[0].exercises[0].sets[0]
        assertNull(set.weightLb); assertEquals(5, set.reps); assertNull(set.rpe); assertFalse(set.isWarmup)
    }
    @Test fun `itemized food keeps per-serving macros and the servings count`() {
        val back = (ShareLinkCodec.decode(ShareLinkCodec.encodeFragment(payload)) as ShareDecodeResult.Success).payload
        val f = back.days[0].food!![0]
        assertEquals(2.0, f.servings, 0.0); assertEquals(190.0, f.calories, 0.0)   // NOT 380: multiplication is the importer's job
    }
    @Test fun `decodes the link LIFT Android's shipped encoder produced`() {
        val r = ShareLinkCodec.decode(fixture())
        val p = (r as ShareDecodeResult.Success).payload
        assertEquals("a1b2c3d4", p.client.id)
        assertTrue("the Android fixture must carry a servings value other than 1", p.days.flatMap { it.food ?: emptyList() }.any { it.servings != 1.0 })
    }
    @Test fun `absent optional client fields stay null`() {
        val j = JSONObject("""{"v":1,"c":{"i":"x","n":"N","u":"kg","p":"ios"},"r":"2026-09-01","t":"2026-09-01","z":1,"d":[]}""")
        val p = (ShareLinkCodec.decode("1u" + CompactEncoding.base64Url(j.toString().toByteArray())) as ShareDecodeResult.Success).payload
        assertNull(p.client.sex); assertNull(p.client.age); assertEquals("kg", p.client.unit)
    }
    @Test fun `rejects unsupported version and malformed payloads`() {
        assertEquals(ShareDecodeResult.UnsupportedVersion, ShareLinkCodec.decode("2zAAAA"))
        assertEquals(ShareDecodeResult.MalformedPayload, ShareLinkCodec.decode("1zNOT-DEFLATE"))
        assertEquals(ShareDecodeResult.MalformedPayload, ShareLinkCodec.decode("1"))
    }
    @Test fun `a day with no ft did not log food and a day with ft zeros did`() {
        val j = JSONObject("""{"v":1,"c":{"i":"x","n":"N"},"r":"2026-09-01","t":"2026-09-02","z":1,"d":[{"k":0},{"k":1,"ft":[0,0,0,0,0]}]}""")
        val p = (ShareLinkCodec.decode("1u" + CompactEncoding.base64Url(j.toString().toByteArray())) as ShareDecodeResult.Success).payload
        assertNull(p.days[0].foodTotals); assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 0.0), p.days[1].foodTotals)
    }
}
