package com.dugcanlift.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

private fun encodeForTest(json: String, compressed: Boolean = true): String {
    val bytes = if (compressed) {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(json.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream()
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        out.toByteArray()
    } else {
        json.toByteArray(Charsets.UTF_8)
    }
    val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    return "1${if (compressed) "z" else "u"}$b64"
}

class PlanLinkCodecTest {

    private val examplePayload = """
        {"v":1,"t":"plan","l":"a1b2c3d4","n":"Doug",
         "r":[{"n":"Beef Chilli","s":4,"u":[438,36,31,19,9],
               "i":["500 g lean beef mince","2 cloves garlic","1 can kidney beans"],
               "t":["Brown the mince."]}],
         "m":[{"d":"2026-08-26","s":2,"x":0,"q":2}]}
    """.trimIndent()

    @Test
    fun `decodes compressed payload addressed to this device`() {
        val result = PlanLinkCodec.decode(encodeForTest(examplePayload), expectedLifterId = "a1b2c3d4")
        assertTrue(result is PlanDecodeResult.Success)
        val payload = (result as PlanDecodeResult.Success).payload
        assertEquals("Doug", payload.coachName)
        assertEquals(1, payload.recipes.size)
        assertEquals("Beef Chilli", payload.recipes[0].name)
        assertEquals(438.0, payload.recipes[0].nutritionPerServing?.calories)
        assertEquals(1, payload.meals.size)
        assertEquals("2026-08-26", payload.meals[0].date)
    }

    @Test
    fun `decodes uncompressed fallback`() {
        val result = PlanLinkCodec.decode(encodeForTest(examplePayload, compressed = false), expectedLifterId = "a1b2c3d4")
        assertTrue(result is PlanDecodeResult.Success)
    }

    @Test
    fun `rejects plan addressed to someone else`() {
        val result = PlanLinkCodec.decode(encodeForTest(examplePayload), expectedLifterId = "someone-else")
        assertEquals(PlanDecodeResult.NotAddressedToYou, result)
    }

    @Test
    fun `rejects unsupported version`() {
        val result = PlanLinkCodec.decode("2z" + encodeForTest(examplePayload).substring(2), expectedLifterId = "a1b2c3d4")
        assertEquals(PlanDecodeResult.UnsupportedVersion, result)
    }

    @Test
    fun `rejects corrupt compressed payload`() {
        val result = PlanLinkCodec.decode("1zNOT-VALID-DEFLATE-DATA", expectedLifterId = "a1b2c3d4")
        assertEquals(PlanDecodeResult.MalformedPayload, result)
    }

    @Test
    fun `rejects fragment too short to contain version and codec`() {
        val result = PlanLinkCodec.decode("1", expectedLifterId = "a1b2c3d4")
        assertEquals(PlanDecodeResult.MalformedPayload, result)
    }

    @Test
    fun `parses a workout template with a ramping set`() {
        val json = """
            {"v":1,"t":"plan","l":"lifter1","n":"Coach",
             "w":[{"n":"Lower A","e":[{"n":"Back Squat","q":"Barbell",
                    "s":[[225,5,8],[225,5,8],[245,3,9]],"c":"Belt on the last set."}]}],
             "k":[{"d":"2026-09-10","x":0}]}
        """.trimIndent()
        val result = PlanLinkCodec.decode(encodeForTest(json), expectedLifterId = "lifter1")
        assertTrue(result is PlanDecodeResult.Success)
        val payload = (result as PlanDecodeResult.Success).payload
        assertEquals(1, payload.workouts.size)
        val exercise = payload.workouts[0].exercises[0]
        assertEquals(3, exercise.sets.size)
        assertEquals(245.0, exercise.sets[2].weightLb)
        assertEquals(9.0, exercise.sets[2].rpe)
        assertEquals(1, payload.sessions.size)
    }

    @Test
    fun `skips a malformed entry instead of rejecting the whole payload`() {
        // "r" has one entry that isn't even a JSON object (would have thrown with strict
        // getJSONObject), and a nutrition array with a string where a number belongs (would have
        // thrown with strict getDouble). Both should be tolerated: the bad recipe entry is skipped,
        // the bad nutrition field falls back to a default — the rest of the payload still decodes.
        val json = """
            {"v":1,"t":"plan","l":"lifter1","n":"Coach",
             "r":[{"n":"Good Recipe","s":2,"u":["not-a-number",36,31,19,9]},
                  ["not","an","object"],
                  {"n":"Second Good","s":1}],
             "m":[{"d":"2026-09-10","s":2,"x":0,"q":1}],
             "w":[{"n":"Leg Day"}]}
        """.trimIndent()
        val result = PlanLinkCodec.decode(encodeForTest(json), expectedLifterId = "lifter1")
        assertTrue(result is PlanDecodeResult.Success)
        val payload = (result as PlanDecodeResult.Success).payload
        assertEquals(2, payload.recipes.size)
        assertEquals("Good Recipe", payload.recipes[0].name)
        assertEquals(0.0, payload.recipes[0].nutritionPerServing?.calories)
        assertEquals("Second Good", payload.recipes[1].name)
        assertEquals(1, payload.meals.size)
        assertEquals(1, payload.workouts.size)
    }
}
