package com.dugcanlift.kit
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DayKeyTest {
    private val chicago: ZoneId = ZoneId.of("America/Chicago")
    @Test fun `a 7 30pm Central log keys to that same local date`() {
        // 2026-09-12 19:30 CDT is 2026-09-13 00:30 UTC. UTC keying misfiles it.
        val millis = ZonedDateTime.of(2026, 9, 12, 19, 30, 0, 0, chicago).toInstant().toEpochMilli()
        assertEquals("2026-09-12", DayKey.make(millis, chicago))
    }
    @Test fun `adding days crosses the DST fall-back without repeating a day`() {
        assertEquals("2026-11-02", DayKey.adding(3, "2026-10-30"))
        assertEquals("2026-11-05", DayKey.adding(7, "2026-10-29"))
    }
    @Test fun `days between is calendar days`() = assertEquals(7, DayKey.daysBetween("2026-10-29", "2026-11-05"))
    @Test fun `an unparseable key is null not an exception`() = assertNull(DayKey.parse("not-a-day"))
}
