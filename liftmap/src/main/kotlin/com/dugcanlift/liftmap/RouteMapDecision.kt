package com.dugcanlift.liftmap

/** Whether a route is drawn over downloaded streets or as the plain line. */
enum class RouteMapDecision {
    MAP, LINE;

    companion object {
        /** [points] are `(latitude, longitude)`. Fewer than two is always the line. */
        fun decide(points: List<Pair<Double, Double>>, covered: (MapBounds) -> Boolean): RouteMapDecision {
            if (points.size < 2) return LINE
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
