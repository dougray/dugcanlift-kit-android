package com.dugcanlift.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * PLAN-FORMAT "Sides": `b: 1` on an exercise done each side, and SHARE-FORMAT's flags bits 1-2 in a
 * sixth set position for a set on one side.
 *
 * `fixtures/web-plan-per-side.txt` is a link Coach web's own encoder wrote (dugcanlift-coach
 * `coach/fixtures/`, the same bytes). Never regenerate it from this code: agreeing with itself
 * proves nothing.
 */
class PlanSidesTest {

    private fun fixture(): PlanPayload {
        val link = javaClass.getResourceAsStream("/fixtures/web-plan-per-side.txt")!!.bufferedReader().readText().trim()
        val result = PlanLinkCodec.decode(link.substringAfter('#'), expectedLifterId = "a1b2c3d4")
        return (result as PlanDecodeResult.Success).payload
    }

    private fun plain(json: String): PlanPayload {
        val fragment = "1u" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        return (PlanLinkCodec.decode(fragment, "x") as PlanDecodeResult.Success).payload
    }

    @Test
    fun `the fixture decodes with its sides`() {
        val e = fixture().workouts.single().exercises
        assertEquals(listOf(false, true, true, false, false), e.map { it.eachSide })
        assertEquals(listOf(null, null, null), e[0].sets.map { it.side })
        assertEquals(listOf(null, null, null, ShareSide.LEFT), e[2].sets.map { it.side })
        assertEquals(listOf(null, null, ShareSide.RIGHT), e[3].sets.map { it.side })
        // Interior nulls kept: the distance is in position 4, not the weight slot.
        assertEquals(listOf(PlanSet(durationSec = 600, distanceMeters = 1600.0, side = ShareSide.LEFT)), e[4].sets)
        assertEquals(PlanSet(weightLb = 40.0, reps = 10, side = ShareSide.RIGHT), e[3].sets[2])
        assertEquals(PlanSet(weightLb = 225.0, reps = 5, rpe = 8.0), e[0].sets[0])
        assertEquals("Extra set on the left.", e[2].note)
    }

    @Test
    fun `flags are masked, never compared - 2, 4, 3, 5, 6`() {
        val flags = listOf(2, 4, 3, 5, 6, 0, 1)
        val tuples = flags.joinToString(",") { "[30,8,null,null,null,$it]" }
        val sets = plain("""{"v":1,"t":"plan","l":"x","w":[{"n":"x","e":[{"n":"Row","s":[$tuples,[30,8],[30,8,null,null,null,"x"]]}]}]}""")
            .workouts.single().exercises.single().sets
        assertEquals(
            listOf(ShareSide.LEFT, ShareSide.RIGHT, ShareSide.LEFT, ShareSide.RIGHT, null, null, null, null, null),
            sets.map { it.side }
        )
        sets.forEach { assertEquals(30.0, it.weightLb); assertEquals(8, it.reps) }
    }

    @Test
    fun `b reads 1 as each side and everything else as not`() {
        fun each(b: String) = plain("""{"v":1,"t":"plan","l":"x","w":[{"n":"x","e":[{"n":"Row"$b,"s":[[30,8]]}]}]}""")
            .workouts.single().exercises.single().eachSide
        assertTrue(each(""","b":1"""))
        assertFalse(each(""))
        assertFalse(each(""","b":0"""))
        assertFalse(each(""","b":2"""))
        assertFalse(each(""","b":"yes""""))
        assertFalse(each(""","b":null"""))
    }

    @Test
    fun `a plan without sides decodes as it always did`() {
        val e = plain("""{"v":1,"t":"plan","l":"x","w":[{"n":"Lower A","e":[{"n":"Back Squat","q":"Barbell","s":[[225,5,8],[null,null,null,600,1600]],"c":"Belt"}]}]}""")
            .workouts.single().exercises.single()
        assertEquals(PlanWorkoutExercise("Back Squat", "Barbell", "Belt",
            listOf(PlanSet(225.0, 5, 8.0), PlanSet(durationSec = 600, distanceMeters = 1600.0))), e)
        assertFalse(e.eachSide)
        assertNull(e.sets[0].side)
    }
}
