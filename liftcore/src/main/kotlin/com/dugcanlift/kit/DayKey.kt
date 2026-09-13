package com.dugcanlift.kit
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Local `yyyy-MM-dd` day keys. Arithmetic through LocalDate, never seconds: seconds repeat a day across a DST fall-back. */
object DayKey {
    private val fmt = DateTimeFormatter.ISO_LOCAL_DATE
    fun make(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().format(fmt)
    fun today(zone: ZoneId = ZoneId.systemDefault()): String = LocalDate.now(zone).format(fmt)
    fun parse(key: String): LocalDate? = runCatching { LocalDate.parse(key, fmt) }.getOrNull()
    fun adding(days: Int, to: String): String = LocalDate.parse(to, fmt).plusDays(days.toLong()).format(fmt)
    fun daysBetween(from: String, to: String): Int = ChronoUnit.DAYS.between(LocalDate.parse(from, fmt), LocalDate.parse(to, fmt)).toInt()
}
