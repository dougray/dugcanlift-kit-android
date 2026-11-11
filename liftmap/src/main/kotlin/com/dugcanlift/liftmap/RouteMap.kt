package com.dugcanlift.liftmap

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first
import com.dugcanlift.kit.DclPalette
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Dark when the app's own Material theme is: the apps theme from DclPalette
 * and let the person pick light, dark or the system's, so the theme's
 * background is the answer, not the system setting.
 */
@Composable
internal fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** What every map says in its corner. */
const val MAP_ATTRIBUTION = StreetStore.ATTRIBUTION

/**
 * A route over the streets the person downloaded, or [fallback] -- the app's
 * own route canvas, unchanged -- when no downloaded area covers it, so a run
 * outside every area looks exactly as it did before maps existed. Partial
 * cover is cover: tiles outside the download are simply empty background.
 *
 * [points] are `(latitude, longitude)`. The map takes [modifier] with
 * [aspectRatio] (width over height), which should be the canvas's own, so a
 * card does not change shape when streets arrive.
 *
 * Drawn only from this device's files: the style names the MBTiles store and
 * the bundled glyphs and nothing else (`Style.Builder().fromJson`), and
 * [MapNetworkGuard] refuses anything MapLibre asks for anyway. Viewing a map
 * makes no request. The decision is remade when the store changes (a download
 * finishes, an area is deleted) and when the route's bounds change, never on
 * every recomposition: a recording recomposes on each GPS fix.
 *
 * **Writes pause maps, by handshake.** A writer (a download, a resume,
 * `StreetStore.removeArea`) raises the store's gate, then waits for every open
 * map to close, and only then writes. While the gate is up this draws the line
 * instead, so its MapView is disposed, and it counts itself closed only after
 * `onDestroy`. When the write ends, the map is made again from the file as it
 * now is. Disposing the MapView stops MapLibre's reads; MapLibre may still keep
 * an idle connection to the file open, which reads nothing.
 *
 * The route stays the strongest thing on screen: a 5 dp accent line with round
 * caps, the start dot in the secondary accent (10 dp) and the end dot in the
 * accent (12 dp). The camera fits the whole route with 24 dp to spare (50 dp
 * at the bottom, clear of the attribution; [RouteFitPadding]) and fits
 * again as the route grows. No location dot, no compass, no logo, no rotation;
 * pan and zoom only when [interactive]. A map that is not interactive lets
 * taps through to whatever holds it (a card that opens the run).
 */
@Composable
fun RouteMap(
    points: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 2f,
    interactive: Boolean = false,
    fallback: @Composable (Modifier) -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { runCatching { StreetStore.standard(context) }.getOrNull() }
    if (store == null) {
        fallback(modifier)
        return
    }
    val revision by store.revision.collectAsState()
    // Writes pause maps: while a download or a delete writes the file, the
    // line is drawn and the MapView disposed, so MapLibre stops reading it.
    val writing by store.writing.collectAsState()
    val dark = isDarkTheme()

    // SQLite is asked only when the store changes; the bounds test is in memory.
    val areas = remember(store, revision, writing) {
        if (writing) emptyList() else runCatching { store.areas() }.getOrDefault(emptyList())
    }
    val bounds = if (points.size >= 2) MapBounds.around(points) else null
    // Keyed on the bounds, not the points: the answer depends on nothing else.
    // The gate is also read directly: the collected copy can lag behind a writer.
    val decision = remember(bounds, areas, writing) {
        RouteMapDecision.decide(points, writing || store.writing.value) { b -> areas.any { it.detail.intersects(b) } }
    }
    val style = remember(decision, dark, store) {
        if (decision != RouteMapDecision.MAP) null
        else runCatching { MapStyle.json(context, dark, store.file) }.getOrNull()
    }
    if (style == null) {
        // No area covers the route, or (a build defect) the style is missing: never a blank square.
        fallback(modifier)
        return
    }
    // A writer may have raised the gate since `writing` was read; then the
    // store refuses the open, no MapView is made, and the line is drawn until
    // the gate comes down and the open is tried again.
    val mapView = rememberMapView(dark, store)
    if (mapView == null) {
        fallback(modifier)
        return
    }

    val background = Color(if (dark) DclPalette.SURFACE else DclPalette.SURFACE_LIGHT)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clearAndSetSemantics { contentDescription = "Route" },
    ) {
        StreetMap(mapView, points, style, revision, dark, interactive, Modifier.matchParentSize())
        if (!interactive) {
            // Above the map, so the MapView never sees a touch; it consumes
            // nothing, so a tap still reaches the card that holds the map.
            Box(
                Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                },
            )
        }
        Text(
            text = MAP_ATTRIBUTION,
            style = MaterialTheme.typography.labelSmall,
            color = Color(if (dark) DclPalette.MUTED else DclPalette.MUTED_LIGHT),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .background(Color(if (dark) DclPalette.BG else DclPalette.BG_LIGHT).copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StreetMap(
    mapView: MapView,
    points: List<Pair<Double, Double>>,
    style: String,
    revision: Int,
    dark: Boolean,
    interactive: Boolean,
    modifier: Modifier,
) {
    val padding = RouteFitPadding.forDensity(LocalDensity.current.density)
    val controller = remember(mapView) { RouteMapController(mapView, padding) }
    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { controller.show(points, style, revision, dark, interactive) },
    )
}

/**
 * One MapView counted open on [store] for as long as it lives: counted before
 * it is made (in composition, before any style is set; see [open]) and counted
 * closed only after its `onDestroy`, so a writer waiting in `awaitMapsClosed`
 * never writes while this view could still be reading. Forgotten after the
 * lifecycle effect below is disposed (Compose forgets in reverse order), so
 * `onPause`/`onStop` come before `onDestroy`.
 */
private class OpenMap private constructor(val view: MapView, private val store: StreetStore) : RememberObserver {
    private var closed = false

    companion object {
        /**
         * Counts a map open and makes its view, or returns null when the store
         * refuses (a writer holds the gate): then no MapView is made at all.
         */
        fun open(store: StreetStore, make: () -> MapView): OpenMap? {
            if (!store.mapOpened()) return null
            val view = try {
                make()
            } catch (e: Throwable) {
                store.mapClosed()
                throw e
            }
            return OpenMap(view, store)
        }
    }

    override fun onRemembered() {}
    override fun onForgotten() = close()
    override fun onAbandoned() = close()

    private fun close() {
        if (closed) return
        closed = true
        try {
            view.onDestroy()
        } finally {
            store.mapClosed()
        }
    }
}

/**
 * A MapView whose lifecycle follows the screen's (nomad-pocket's
 * `rememberMapView`), or null while the store refuses maps (a writer holds the
 * gate); the open is tried again once the gate comes down.
 */
@Composable
private fun rememberMapView(dark: Boolean, store: StreetStore): MapView? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var attempt by remember(store) { mutableIntStateOf(0) }
    val open = remember(store, attempt) { OpenMap.open(store) { newMapView(context, dark) } }
    if (open == null) {
        LaunchedEffect(store, attempt) {
            store.writing.first { !it }
            attempt++
        }
        return null
    }
    val mapView = open.view
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            // onDestroy, and the count, belong to OpenMap, forgotten after this.
        }
    }
    return mapView
}

private fun newMapView(context: Context, dark: Boolean): MapView {
    MapNetworkGuard.install(context)
    val options = MapLibreMapOptions.createFromAttributes(context)
        // A TextureView clips and scrolls with Compose like any other view.
        .textureMode(true)
        .logoEnabled(false)
        .attributionEnabled(false)
        .compassEnabled(false)
        .foregroundLoadColor((if (dark) DclPalette.SURFACE else DclPalette.SURFACE_LIGHT).toInt())
    // No style is set until `show`: the only style a map gets is the local JSON.
    return MapView(context, options).apply { onCreate(null) }
}

/** The route's sizes and layer names, the same as the Swift kit's `StreetMapView`. */
internal object RouteLayers {
    const val LINE_WIDTH = 5f
    const val START_RADIUS = 5f // a 10 dp dot
    const val END_RADIUS = 6f // a 12 dp dot
    /** A route that has barely moved would zoom past every street; show a block or two around it. */
    const val MINIMUM_SPAN = 0.002

    const val ROUTE_SOURCE = "lift-route"
    const val START_SOURCE = "lift-route-start"
    const val END_SOURCE = "lift-route-end"

    /** The box the camera fits: the route's, at least [MINIMUM_SPAN] each way. */
    fun fitBounds(points: List<Pair<Double, Double>>): MapBounds? {
        if (points.size < 2) return null
        var b = MapBounds.around(points) ?: return null
        if (b.north - b.south < MINIMUM_SPAN) {
            val mid = (b.north + b.south) / 2
            b = b.copy(south = mid - MINIMUM_SPAN / 2, north = mid + MINIMUM_SPAN / 2)
        }
        if (b.east - b.west < MINIMUM_SPAN) {
            val mid = (b.east + b.west) / 2
            b = b.copy(west = mid - MINIMUM_SPAN / 2, east = mid + MINIMUM_SPAN / 2)
        }
        return b
    }
}

/** Puts the style and the route on one MapView and keeps the camera on the route. */
private class RouteMapController(private val view: MapView, private val padding: RouteFitPadding) {
    private var map: MapLibreMap? = null
    private var loaded: Style? = null
    private var styleJson: String? = null
    /** The store revision the loaded style read its tiles at. */
    private var styleRevision: Int? = null
    private var points: List<Pair<Double, Double>> = emptyList()
    private var dark = false
    private var interactive = false

    init {
        view.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isLogoEnabled = false
                isAttributionEnabled = false
                isCompassEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
            }
            sync()
        }
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) fit()
        }
    }

    /** The style this view should show; null until `show` first runs. */
    private var wanted: String? = null
    private var wantedRevision = 0

    fun show(points: List<Pair<Double, Double>>, style: String, revision: Int, dark: Boolean, interactive: Boolean) {
        val moved = points != this.points
        this.points = points
        this.dark = dark
        this.interactive = interactive
        wanted = style
        wantedRevision = revision
        sync(moved)
    }

    private fun sync(moved: Boolean = true) {
        val m = map ?: return
        gestures()
        val json = wanted ?: return
        val revision = wantedRevision
        if (StyleReload.needed(json, styleJson, revision, styleRevision)) {
            styleJson = json
            styleRevision = revision
            loaded = null
            // A new store revision loads the same style again, so MapLibre reads
            // the file afresh: new streets appear, deleted ones go. The route
            // layers go back on, in the current colors, when the style loads.
            m.setStyle(Style.Builder().fromJson(json)) { s ->
                if (styleJson != json || styleRevision != revision) return@setStyle
                loaded = s
                addRoute(s)
                fit()
            }
        } else if (moved) {
            loaded?.let { updateRoute(it) }
            fit()
        }
    }

    private fun gestures() {
        map?.uiSettings?.apply {
            isScrollGesturesEnabled = interactive
            isZoomGesturesEnabled = interactive
            isDoubleTapGesturesEnabled = interactive
            isQuickZoomGesturesEnabled = interactive
        }
    }

    private fun line() = LineString.fromLngLats(points.map { (lat, lon) -> Point.fromLngLat(lon, lat) })
    private fun start() = points.first().let { (lat, lon) -> Point.fromLngLat(lon, lat) }
    private fun end() = points.last().let { (lat, lon) -> Point.fromLngLat(lon, lat) }

    /** Puts the route on top of every street layer. */
    private fun addRoute(style: Style) {
        if (points.size < 2) return
        val accent = (if (dark) DclPalette.ACCENT else DclPalette.ACCENT_LIGHT).toInt()
        val secondary = (if (dark) DclPalette.ACCENT2 else DclPalette.ACCENT2_LIGHT).toInt()
        style.addSource(GeoJsonSource(RouteLayers.ROUTE_SOURCE, line()))
        style.addLayer(
            LineLayer("lift-route-line", RouteLayers.ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor(accent),
                PropertyFactory.lineWidth(RouteLayers.LINE_WIDTH),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addSource(GeoJsonSource(RouteLayers.START_SOURCE, start()))
        style.addLayer(
            CircleLayer("lift-route-start-dot", RouteLayers.START_SOURCE).withProperties(
                PropertyFactory.circleColor(secondary),
                PropertyFactory.circleRadius(RouteLayers.START_RADIUS),
            ),
        )
        style.addSource(GeoJsonSource(RouteLayers.END_SOURCE, end()))
        style.addLayer(
            CircleLayer("lift-route-end-dot", RouteLayers.END_SOURCE).withProperties(
                PropertyFactory.circleColor(accent),
                PropertyFactory.circleRadius(RouteLayers.END_RADIUS),
            ),
        )
    }

    /** Updates the route in place, or adds it if it was too short to draw before. */
    private fun updateRoute(style: Style) {
        val route = style.getSourceAs<GeoJsonSource>(RouteLayers.ROUTE_SOURCE)
        if (route == null) {
            addRoute(style)
            return
        }
        if (points.size < 2) return
        route.setGeoJson(line())
        style.getSourceAs<GeoJsonSource>(RouteLayers.START_SOURCE)?.setGeoJson(start())
        style.getSourceAs<GeoJsonSource>(RouteLayers.END_SOURCE)?.setGeoJson(end())
    }

    /** Fits the whole route inside [padding], once the view has a size. */
    private fun fit() {
        val m = map ?: return
        if (!padding.fits(view.width, view.height)) return
        val b = RouteLayers.fitBounds(points) ?: return
        m.moveCamera(
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.from(b.north, b.east, b.south, b.west),
                padding.left, padding.top, padding.right, padding.bottom,
            ),
        )
    }
}
