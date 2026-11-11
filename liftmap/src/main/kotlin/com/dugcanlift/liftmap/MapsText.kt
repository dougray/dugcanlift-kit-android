package com.dugcanlift.liftmap

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The words the Maps card and the streets sheet say, and the plans they ask
 * for -- the Swift kit's `MapsSection` / `StreetsSheet` copy, word for word,
 * kept out of the views so it can be tested.
 */
object MapsText {
    const val TITLE = "Maps"
    const val FOOTER = "Streets are drawn from areas you download. Nothing is sent while you look at a map."
    const val NONE = "No streets downloaded."
    const val NOT_FINISHED = "Not finished"
    const val AROUND_HERE = "Download streets around here"
    const val AROUND_LAST_RUN = "Download streets around your last run"
    const val FOR_ROUTE = "Download streets for this route"
    const val ROUTE_AREA_NAME = "Streets for this route"
    const val UPDATE = "Update"
    const val DELETE = "Delete"
    const val TRY_AGAIN = "Try again"
    const val CANCEL = "Cancel"
    const val DELETE_MESSAGE = "You can download it again later."

    const val SHEET_TITLE = "Download streets"
    const val DOWNLOAD = "Download"
    const val STREETS_WITHIN = "Streets within"
    const val DOWNLOADING = "Downloading streets"
    const val SOURCE = "OpenStreetMap data from OpenFreeMap"
    const val SERVER_LEARNS = "OpenFreeMap learns which area you asked for, once."
    const val FAILURE = "Couldn't finish. Try again to pick up where it stopped."

    /** The radius a new area starts at; the sheet offers the others. */
    val STARTING_KM = StreetAreaPlan.DETAIL_CHOICES[0]

    fun deleteTitle(area: StreetArea) = "Delete ${area.name}?"

    fun radius(km: Int) = "$km km"

    /** "About 40 MB". */
    fun sizeText(bytes: Long) = "About " + fileSize(bytes)

    /** "24.8 MB · Nov 10, 2026". */
    fun detailLine(area: StreetArea, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()) =
        fileSize(area.bytes) + " · " + date(area.downloadedAt, zone, locale)

    fun date(at: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(at.atZone(zone))

    /**
     * A file size the way Apple's `ByteCountFormatter` (file style) writes
     * one: powers of 1000, whole kilobytes, megabytes to a tenth and gigabytes
     * to a hundredth, trailing zeros dropped. "Zero KB", "999 bytes", "24.8 MB".
     */
    fun fileSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
        fun trimmed(v: Double, places: Int): String {
            val scale = Math.pow(10.0, places.toDouble())
            val r = (v * scale).roundToLong() / scale
            return String.format(locale, "%.${places}f", r).let { s ->
                if (places == 0) s else s.trimEnd('0').trimEnd('.', ',')
            }
        }
        return when {
            bytes <= 0L -> "Zero KB"
            bytes == 1L -> "1 byte"
            bytes < 1_000L -> "$bytes bytes"
            bytes < 999_500L -> trimmed(bytes / 1e3, 0) + " KB"
            bytes < 999_950_000L -> trimmed(bytes / 1e6, 1) + " MB"
            else -> trimmed(bytes / 1e9, 2) + " GB"
        }
    }

    /**
     * "Streets around 39.74, -104.99", as Nomad Pocket names its areas. No
     * place name: finding one would be a request.
     */
    fun nameAround(point: Pair<Double, Double>) =
        "Streets around " + String.format(Locale.US, "%.2f, %.2f", point.first, point.second)

    /**
     * The plan an area was downloaded with, for an update or a retry. A radius
     * area is the same radius around the centre of its detail box. A route
     * area (`detailKm` 0) stored the route's box already grown 2 km, so it is
     * shrunk back by those 2 km first, and `forRoute` grows it to the same box.
     */
    fun replan(area: StreetArea): StreetAreaPlan {
        val d = area.detail
        val lat = (d.south + d.north) / 2
        val lon = (d.west + d.east) / 2
        if (area.detailKm > 0) return StreetAreaPlan(lat, lon, area.detailKm)
        val dLat = 2 / 111.32
        val dLon = 2 / (111.32 * max(0.01, cos(lat * PI / 180)))
        val route = MapBounds(
            south = min(lat, d.south + dLat), west = min(lon, d.west + dLon),
            north = max(lat, d.north - dLat), east = max(lon, d.east - dLon),
        )
        return StreetAreaPlan.forRoute(route)
    }
}
