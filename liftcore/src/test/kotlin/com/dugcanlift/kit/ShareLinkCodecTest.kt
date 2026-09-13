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
        assertEquals("Doug", p.client.name)
        assertEquals("and", p.client.platform)
        assertEquals("2026-08-16", p.startDay)
        assertEquals("2026-09-12", p.endDay)
        assertNotNull(p.goal)
        assertEquals(2400, p.goal!!.calories); assertEquals(180, p.goal!!.proteinG); assertEquals(70, p.goal!!.fatG); assertEquals(220, p.goal!!.carbsG); assertEquals(35, p.goal!!.fiberG)
        assertEquals(3, p.days.size)
        // Day 0: exercises + food
        val d0 = p.days[0]
        assertEquals(24, d0.dayOffset); assertEquals("Squat Day", d0.sessionName); assertEquals("BODYBUILDING", d0.focus)
        assertEquals(1, d0.exercises.size); assertEquals("Back Squat", d0.exercises[0].name); assertEquals("Barbell", d0.exercises[0].equipment)
        assertEquals(3, d0.exercises[0].sets.size); assertEquals(225.0, d0.exercises[0].sets[1].weightLb ?: 0.0, 0.0); assertEquals(5, d0.exercises[0].sets[1].reps)
        assertEquals(1, d0.food?.size); assertEquals("Greek Yogurt", d0.food!![0].name); assertEquals(2.0, d0.food!![0].servings, 0.0); assertEquals(0, d0.food!![0].meal)
        // Day 1: only food (no exercises)
        val d1 = p.days[1]
        assertEquals(25, d1.dayOffset); assertNull(d1.sessionName); assertNull(d1.focus)
        assertTrue(d1.exercises.isEmpty()); assertEquals(1, d1.food?.size); assertEquals("Brown Rice", d1.food!![0].name); assertEquals(2, d1.food!![0].meal)
        // Day 2: exercises + food + RPE
        val d2 = p.days[2]
        assertEquals("Cable Row", d2.exercises[0].name); assertEquals("Cable", d2.exercises[0].equipment)
        assertEquals(2, d2.exercises[0].sets.size); assertNull(d2.exercises[0].sets[1].weightLb); assertNull(d2.exercises[0].sets[1].reps); assertEquals(8.5, d2.exercises[0].sets[1].rpe ?: 0.0, 0.0)
        assertEquals("Chicken Breast", d2.food!![0].name); assertEquals(1, d2.food!![0].meal)
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
    @Test fun `encodes optional client fields and foodTotals round-trip`() {
        val p = SharePayload(
            client = ShareClient("xyz", "Pat", sex = "M", age = 32, heightIn = 72.0, unit = "kg"),
            goal = null,
            startDay = "2026-01-01", endDay = "2026-01-03", exportedAtEpochSeconds = 1000,
            days = listOf(
                ShareDay(0, null, null, null, null, emptyList(), null, null),  // Empty day, should be skipped
                ShareDay(1, null, null, null, null, emptyList(), listOf(2400.0, 150.0, 85.0, 280.0, 40.0), null),  // foodTotals only
                ShareDay(2, "Test", "STRENGTH", 200.0, 10000, listOf(ShareExercise("Bench", "Barbell", emptyList())), null, null)  // exercises + optional fields
            ))
        val back = (ShareLinkCodec.decode(ShareLinkCodec.encodeFragment(p)) as ShareDecodeResult.Success).payload
        assertEquals("M", back.client.sex); assertEquals(32, back.client.age); assertEquals(72.0, back.client.heightIn ?: 0.0, 0.0); assertEquals("kg", back.client.unit)
        assertEquals(2, back.days.size)  // First day (empty) is skipped
        assertEquals(1, back.days[0].dayOffset); assertEquals(listOf(2400.0, 150.0, 85.0, 280.0, 40.0), back.days[0].foodTotals)
        assertEquals(2, back.days[1].dayOffset); assertEquals("Test", back.days[1].sessionName); assertEquals("STRENGTH", back.days[1].focus); assertEquals(200.0, back.days[1].bodyweightLb ?: 0.0, 0.0); assertEquals(10000L, back.days[1].steps)
    }
    @Test fun `a fractional height round trips and is not truncated on the wire`() {
        val p = SharePayload(
            client = ShareClient("frac1", "Frac", heightIn = 70.5),
            goal = null,
            startDay = "2026-01-01", endDay = "2026-01-01", exportedAtEpochSeconds = 1000,
            days = emptyList())
        val json = ShareLinkCodec.buildJson(p)
        assertEquals(70.5, json.getJSONObject("c").getDouble("h"), 0.0)
        val back = (ShareLinkCodec.decode(ShareLinkCodec.encodeFragment(p)) as ShareDecodeResult.Success).payload
        assertEquals(70.5, back.client.heightIn ?: 0.0, 0.0)
    }
    @Test fun `days with only steps are emitted and days with all empty fields are skipped`() {
        val p = SharePayload(
            client = ShareClient("a", "A"),
            goal = null,
            startDay = "2026-01-01", endDay = "2026-01-05", exportedAtEpochSeconds = 1000,
            days = listOf(
                ShareDay(0, null, null, null, null, emptyList(), null, null),  // Empty, skipped
                ShareDay(1, null, null, null, 5000L, emptyList(), null, null),  // Only steps, emitted
                ShareDay(2, null, null, 185.0, null, emptyList(), null, null),  // Only bodyweight, emitted
                ShareDay(3, null, null, null, null, emptyList(), null, null),  // Empty, skipped
                ShareDay(4, null, null, null, null, emptyList(), listOf(2000.0, 100.0, 50.0, 200.0, 30.0), null)  // Only foodTotals, emitted
            ))
        val back = (ShareLinkCodec.decode(ShareLinkCodec.encodeFragment(p)) as ShareDecodeResult.Success).payload
        assertEquals(3, back.days.size)
        assertEquals(1, back.days[0].dayOffset); assertEquals(5000L, back.days[0].steps)
        assertEquals(2, back.days[1].dayOffset); assertEquals(185.0, back.days[1].bodyweightLb ?: 0.0, 0.0)
        assertEquals(4, back.days[2].dayOffset); assertEquals(listOf(2000.0, 100.0, 50.0, 200.0, 30.0), back.days[2].foodTotals)
    }
}
