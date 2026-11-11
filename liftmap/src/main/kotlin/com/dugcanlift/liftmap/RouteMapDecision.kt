package com.dugcanlift.liftmap

/** Whether a route is drawn over downloaded streets or as the plain line. */
enum class RouteMapDecision {
    MAP, LINE;

    companion object {
        /**
         * [points] are `(latitude, longitude)`. Fewer than two is always the
         * line, and so is a store that is [writing] (`StreetStore.writing`):
         * writes pause maps, so MapLibre never reads the file while it changes.
         */
        fun decide(
            points: List<Pair<Double, Double>>,
            writing: Boolean = false,
            covered: (MapBounds) -> Boolean,
        ): RouteMapDecision {
            if (points.size < 2 || writing) return LINE
            val bounds = MapBounds.around(points) ?: return LINE
            return if (covered(bounds)) MAP else LINE
        }
    }
}

/**
 * Whether a map on screen must load its style again. A new style (light or
 * dark) needs it; so does a new store revision with the same style text: a
 * download finished or an area was deleted, and MapLibre still holds the
 * tiles it read before -- blank where streets now are, drawn where they are
 * gone. Loading the style again makes it read the file afresh.
 */
internal object StyleReload {
    fun needed(json: String, lastJson: String?, revision: Int, lastRevision: Int?): Boolean =
        json != lastJson || revision != lastRevision
}

/**
 * The room left around a route when the camera fits it, in pixels: 24 dp on
 * the left, top and right, and 50 dp at the bottom, where the attribution sits
 * (bottom-trailing), so the text never covers the route or its start dot --
 * the web build's 24 / 50 px.
 */
data class RouteFitPadding(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    /** True when a view this size leaves any room for the route inside the padding. */
    fun fits(width: Int, height: Int): Boolean = width > left + right && height > top + bottom

    companion object {
        const val SIDE_DP = 24f
        const val BOTTOM_DP = 50f

        /** [density] is pixels per dp. */
        fun forDensity(density: Float): RouteFitPadding {
            val side = kotlin.math.round(SIDE_DP * density).toInt()
            return RouteFitPadding(side, side, side, kotlin.math.round(BOTTOM_DP * density).toInt())
        }
    }
}
