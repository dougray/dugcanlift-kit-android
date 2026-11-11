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
