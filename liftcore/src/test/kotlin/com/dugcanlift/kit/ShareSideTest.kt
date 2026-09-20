package com.dugcanlift.kit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * SHARE-FORMAT's set tuple carries the side in flags bits 1-2: 0 both, 1 left,
 * 2 right, beside bit 0's warmup flag. No new positions, so a decoder that
 * knows nothing about sides still reads the weight and the reps.
 */
class ShareSideTest {

    private fun payload(vararg sets: ShareSet) = SharePayload(
        client = ShareClient("side1", "Doug", platform = "and"), goal = null,
        startDay = "2026-09-01", endDay = "2026-09-01", exportedAtEpochSeconds = 1_789_000_000,
        days = listOf(ShareDay(0, "Legs", "POWERLIFTING", null, null,
            exercises = listOf(ShareExercise("Bulgarian Split Squat", "Dumbbell", sets.toList())),
            foodTotals = null, food = null)))

    private fun roundTrip(p: SharePayload) =
        (ShareLinkCodec.decode(ShareLinkCodec.encodeFragment(p)) as ShareDecodeResult.Success).payload

    private fun tuples(p: SharePayload) =
        ShareLinkCodec.buildJson(p).getJSONArray("d").getJSONObject(0).getJSONArray("w").getJSONArray(0).getJSONArray(1)

    @Test fun `both left and right survive a round trip`() {
        val p = payload(
            ShareSet(60.0, 8, null, null, null, false),
            ShareSet(60.0, 8, null, null, null, false, ShareSide.LEFT),
            ShareSet(55.0, 8, null, null, null, false, ShareSide.RIGHT))
        val sets = roundTrip(p).days[0].exercises[0].sets
        assertNull(sets[0].side)
        assertEquals(ShareSide.LEFT, sets[1].side)
        assertEquals(ShareSide.RIGHT, sets[2].side)
    }

    @Test fun `the side is the flags field, not a new position`() {
        val p = payload(
            ShareSet(60.0, 8, null, null, null, false, ShareSide.LEFT),
            ShareSet(55.0, 8, null, null, null, false, ShareSide.RIGHT),
            ShareSet(45.0, 8, null, null, null, true, ShareSide.RIGHT))
        val t = tuples(p)
        assertEquals("[60,8,null,null,null,2]", t.getJSONArray(0).toString())
        assertEquals("[55,8,null,null,null,4]", t.getJSONArray(1).toString())
        // warmup and a side share the field: bit 0 plus bits 1-2.
        assertEquals("[45,8,null,null,null,5]", t.getJSONArray(2).toString())
    }

    @Test fun `a both-sided set still trims its zero flags away`() {
        val t = tuples(payload(ShareSet(60.0, 8, null, null, null, false)))
        assertEquals("[60,8]", t.getJSONArray(0).toString())
    }

    @Test fun `a set written before sides existed decodes as both`() {
        val j = JSONObject("""{"v":1,"c":{"i":"x","n":"N"},"r":"2026-09-01","t":"2026-09-01","z":1,"x":["Row|Cable"],"d":[{"k":0,"w":[[0,[[95,10],[95,10,null,null,null,1]]]]}]}""")
        val p = (ShareLinkCodec.decode("1u" + CompactEncoding.base64Url(j.toString().toByteArray())) as ShareDecodeResult.Success).payload
        val sets = p.days[0].exercises[0].sets
        assertNull(sets[0].side)
        assertTrue(sets[1].isWarmup)
        assertNull(sets[1].side)
    }

    @Test fun `side bits leave the numbers a reader that ignores them sees untouched`() {
        val p = payload(
            ShareSet(60.0, 8, 8.5, null, null, false, ShareSide.LEFT),
            ShareSet(55.0, 6, null, null, null, false, ShareSide.RIGHT))
        val t = tuples(p)
        // What a reader that knows nothing of sides takes from the tuple: the
        // five positions it already knew.
        assertEquals(60.0, t.getJSONArray(0).getDouble(0), 0.0)
        assertEquals(8, t.getJSONArray(0).getInt(1))
        assertEquals(8.5, t.getJSONArray(0).getDouble(2), 0.0)
        assertEquals(55.0, t.getJSONArray(1).getDouble(0), 0.0)
        assertEquals(6, t.getJSONArray(1).getInt(1))
        // And the volume it computes from them.
        val volume = (0 until t.length()).sumOf { t.getJSONArray(it).getDouble(0) * t.getJSONArray(it).getInt(1) }
        assertEquals(60.0 * 8 + 55.0 * 6, volume, 0.0)
        // Such a reader tests bit 0 alone: neither of these is a warmup.
        assertEquals(0, t.getJSONArray(0).getInt(5) and 1)
        assertEquals(0, t.getJSONArray(1).getInt(5) and 1)
    }

    @Test fun `an unassigned pair of side bits reads as both`() {
        assertNull(ShareLinkCodec.sideOf(0))
        assertEquals(ShareSide.LEFT, ShareLinkCodec.sideOf(3))   // warmup, left
        assertEquals(ShareSide.RIGHT, ShareLinkCodec.sideOf(4))
        // 3 in bits 1-2 is not a side this format defines; never invent one.
        assertNull(ShareLinkCodec.sideOf(6))
    }
}
