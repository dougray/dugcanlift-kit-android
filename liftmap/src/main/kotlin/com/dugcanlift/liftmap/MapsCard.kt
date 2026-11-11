package com.dugcanlift.liftmap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dugcanlift.kit.DclPalette
import java.util.UUID

/**
 * The Maps card for a settings screen: the areas downloaded, each with
 * **Update** and **Delete**, any download that did not finish with **Try
 * again**, and the ways to download a new area. Nothing downloads without the
 * person tapping through [StreetsSheet], and nothing ever updates on its own.
 *
 * **Update** downloads the same area again as a new area and deletes the old
 * one only once the new one has finished, so a failed update leaves the streets
 * the person had.
 *
 * LIFT passes [aroundHere] (one fix, with the permission it already has; a null
 * answer hides the row) and [lastRunStart], each `(latitude, longitude)`.
 * Coach passes null for both, and offers its route download on the route card
 * instead; [routeArea], when given, adds that offer here too, with [routeNote]
 * under it in the sheet.
 */
@Composable
fun MapsCard(
    store: StreetStore,
    aroundHere: (suspend () -> Pair<Double, Double>?)?,
    lastRunStart: Pair<Double, Double>?,
    modifier: Modifier = Modifier,
    routeArea: MapBounds? = null,
    routeNote: String? = null,
) {
    val revision by store.revision.collectAsState()
    /** Bumped after a sheet closes: a cancel or a failure changes the unfinished list without a revision. */
    var reloads by remember { mutableIntStateOf(0) }
    val areas = remember(store, revision, reloads) { runCatching { store.areas() }.getOrDefault(emptyList()) }
    val unfinished = remember(store, revision, reloads) { runCatching { store.unfinishedAreas() }.getOrDefault(emptyList()) }
    var here by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(aroundHere) { here = aroundHere?.invoke() }
    var sheet by remember { mutableStateOf<SheetRequest?>(null) }
    var deleting by remember { mutableStateOf<StreetArea?>(null) }

    val dark = isDarkTheme()
    val accentText = Color(if (dark) DclPalette.ACCENT_TEXT else DclPalette.ACCENT_TEXT_LIGHT)
    val muted = Color(if (dark) DclPalette.MUTED else DclPalette.MUTED_LIGHT)
    val border = Color(if (dark) DclPalette.CARD_BORDER else DclPalette.CARD_BORDER_LIGHT)
    val textColors = ButtonDefaults.textButtonColors(contentColor = accentText)

    fun offer(point: Pair<Double, Double>) {
        sheet = SheetRequest(
            StreetAreaPlan(point.first, point.second, MapsText.STARTING_KM), MapsText.nameAround(point),
            StreetsSheetMode.New, replacing = null, note = null,
        )
    }

    Card(modifier = modifier.fillMaxWidth(), border = BorderStroke(1.dp, border)) {
        Column(Modifier.padding(16.dp)) {
            Text(MapsText.TITLE, style = MaterialTheme.typography.titleMedium, color = accentText)
            Spacer(Modifier.height(8.dp))
            for (area in areas) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(area.name, style = MaterialTheme.typography.bodyLarge)
                        Text(MapsText.detailLine(area), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    TextButton(onClick = {
                        sheet = SheetRequest(MapsText.replan(area), area.name, StreetsSheetMode.Update, area.id, null)
                    }, colors = textColors) { Text(MapsText.UPDATE) }
                    TextButton(onClick = { deleting = area }, colors = textColors) { Text(MapsText.DELETE) }
                }
            }
            for (area in unfinished) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(area.name, style = MaterialTheme.typography.bodyLarge)
                        Text(MapsText.NOT_FINISHED, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    TextButton(onClick = {
                        sheet = SheetRequest(MapsText.replan(area), area.name, StreetsSheetMode.Resume(area.id), null, null)
                    }, colors = textColors) { Text(MapsText.TRY_AGAIN) }
                    TextButton(onClick = { deleting = area }, colors = textColors) { Text(MapsText.DELETE) }
                }
            }
            if (areas.isEmpty() && unfinished.isEmpty()) {
                Text(MapsText.NONE, style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            here?.let { point ->
                TextButton(onClick = { offer(point) }, colors = textColors) { Text(MapsText.AROUND_HERE) }
            }
            lastRunStart?.let { point ->
                TextButton(onClick = { offer(point) }, colors = textColors) { Text(MapsText.AROUND_LAST_RUN) }
            }
            routeArea?.let { bounds ->
                TextButton(onClick = {
                    sheet = SheetRequest(StreetAreaPlan.forRoute(bounds), MapsText.ROUTE_AREA_NAME, StreetsSheetMode.New, null, routeNote)
                }, colors = textColors) { Text(MapsText.FOR_ROUTE) }
            }
            Spacer(Modifier.height(4.dp))
            Text(MapsText.FOOTER, style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }

    sheet?.let { request ->
        StreetsSheet(request.plan, request.name, store, request.note, request.mode) { area ->
            if (area != null) request.replacing?.let { old -> runCatching { store.deleteArea(old) } }
            sheet = null
            reloads++
        }
    }

    deleting?.let { area ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(MapsText.deleteTitle(area)) },
            text = { Text(MapsText.DELETE_MESSAGE) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { store.deleteArea(area.id) }
                    deleting = null
                    reloads++
                }, colors = textColors) { Text(MapsText.DELETE) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, colors = textColors) { Text(MapsText.CANCEL) }
            },
        )
    }
}

private data class SheetRequest(
    val plan: StreetAreaPlan,
    val name: String,
    val mode: StreetsSheetMode,
    /** The area an update replaces, deleted once the new one finishes. */
    val replacing: UUID?,
    val note: String?,
)
