package com.dugcanlift.liftmap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dugcanlift.kit.DclPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** What a [StreetsSheet] is for. */
internal sealed interface StreetsSheetMode {
    /** A new area. */
    data object New : StreetsSheetMode

    /** The same area again (Maps → Update): the radius is fixed. */
    data object Update : StreetsSheetMode

    /** An area begun before and not finished: only Try again. */
    data class Resume(val areaId: UUID) : StreetsSheetMode
}

/** What a Cancel may do, kept out of the view so it can be tested. */
internal object StreetsSheetRules {
    /**
     * A Cancel removes the area this sheet began (a new area or an update),
     * unless the download already finished and went to the host: that area is
     * the person's now, and in an update the old one is already gone. A retry
     * keeps the unfinished area it came from.
     */
    fun cancelMayDelete(mode: StreetsSheetMode, areaId: UUID?, finished: Boolean): Boolean =
        !finished && mode !is StreetsSheetMode.Resume && areaId != null

    /** A Cancel reports `onDone(null)` only if the sheet has not already reported its area. */
    fun cancelReports(finished: Boolean): Boolean = !finished
}

/** What the download job (main thread) and a Cancel's cleanup (the store's IO scope) share. */
private class SheetSession(areaId: UUID?) {
    /** The area this sheet is filling, once begun. */
    @Volatile var areaId: UUID? = areaId

    /** Set before `onDone(area)`: from then on a Cancel neither deletes nor reports. */
    @Volatile var finished = false
}

private sealed interface Phase {
    data object Ready : Phase
    data object Downloading : Phase
    data object Failed : Phase
}

/**
 * Says what a street download is, how large, from where and what that server
 * learns, before anything is asked for; the download starts only on
 * **Download**. A failed download keeps what it got: **Try again** picks up
 * where it stopped (`MapDownload.resume`).
 *
 * A bottom sheet of its own: show it while you want it, and remove it when
 * [onDone] is called -- with the finished area, or null when the person
 * cancels. It cannot be swiped away while it downloads; Cancel stops the
 * download, waits for it to stop writing, removes the area this sheet began,
 * and only then calls [onDone], so a list reloaded then never shows it.
 *
 * [note] is one more line under what the server learns, for example Coach's
 * "This asks for the area around your client's route."
 */
@Composable
fun StreetsSheet(
    plan: StreetAreaPlan,
    name: String,
    store: StreetStore,
    note: String? = null,
    onDone: (StreetArea?) -> Unit,
) = StreetsSheet(plan, name, store, note, StreetsSheetMode.New, onDone)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StreetsSheet(
    initialPlan: StreetAreaPlan,
    name: String,
    store: StreetStore,
    note: String?,
    mode: StreetsSheetMode,
    onDone: (StreetArea?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf(initialPlan) }
    var phase by remember { mutableStateOf(if (mode is StreetsSheetMode.Resume) Phase.Failed else Phase.Ready) }
    /** The area this sheet is filling and whether it finished, shared with a Cancel's cleanup thread. */
    val session = remember { SheetSession((mode as? StreetsSheetMode.Resume)?.areaId) }
    var job by remember { mutableStateOf<Job?>(null) }
    var stopping by remember { mutableStateOf(false) }
    val progressFlow = remember { MutableStateFlow(0 to 0) }
    val progress by progressFlow.collectAsState()

    val downloading = phase == Phase.Downloading
    // A route plan is not a radius, and an update or a retry is the area it was.
    val showsRadius = plan.detailKm != 0 && mode == StreetsSheetMode.New

    /** Begins the area (once) and fills it; a retry fills the same area. */
    fun start() {
        if (downloading || stopping) return
        val p = plan
        // A writer raises the gate first, waits for open maps to close, and only
        // then writes (beginning the area). The gate covers the whole session and
        // comes down when the job ends, however it ends; a cancel holds its own
        // across its cleanup.
        store.beginWriting()
        progressFlow.value = 0 to p.tiles.size
        phase = Phase.Downloading
        job = scope.launch {
            try {
                store.awaitMapsClosed()
                // Off the main thread; remembered inside the same block, so a
                // cancel arriving as it returns still finds the area.
                val id = session.areaId
                    ?: withContext(Dispatchers.IO) { store.beginArea(p, name).also { session.areaId = it } }
                val area = MapDownload(store).resume(id, p) { done, total -> progressFlow.value = done to total }
                phase = Phase.Ready
                session.finished = true
                onDone(area)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                phase = Phase.Failed
            }
        }.also { it.invokeOnCompletion { store.endWriting() } } // runs even if the job never starts
    }

    /**
     * Stops a download in flight and waits for it to stop writing. An area
     * this sheet began is then removed; an area from before (a retry from the
     * Maps list) is kept, unfinished, as it was. [onDone] comes last.
     */
    fun cancel() {
        if (stopping || !StreetsSheetRules.cancelReports(session.finished)) return
        stopping = true
        val running = job
        // Held from before the download stops until its area is gone, so no map
        // reopens the file in between.
        store.beginWriting()
        // On the store's own scope, not this sheet's: a sheet swiped away or
        // removed while this waits must still remove the area it began.
        store.work.launch(CoroutineExceptionHandler { _, e -> logWarning("Cancelling a street download failed", e) }) {
            try {
                running?.cancelAndJoin()
                // The download may have finished and reported while we waited.
                if (!StreetsSheetRules.cancelReports(session.finished)) return@launch
                // Read after the join: the job may have begun the area while it ran.
                val id = session.areaId
                if (StreetsSheetRules.cancelMayDelete(mode, id, session.finished) && id != null) {
                    try {
                        store.removeArea(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logWarning("Removing a cancelled street download failed", e)
                    }
                }
                withContext(Dispatchers.Main) { onDone(null) }
            } finally {
                store.endWriting()
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || (phase != Phase.Downloading && !stopping && !session.finished) },
    )
    val dark = isDarkTheme()
    val accent = Color(if (dark) DclPalette.ACCENT else DclPalette.ACCENT_LIGHT)
    val accentText = Color(if (dark) DclPalette.ACCENT_TEXT else DclPalette.ACCENT_TEXT_LIGHT)
    val onAccent = Color(if (dark) DclPalette.ON_ACCENT else DclPalette.ON_ACCENT_LIGHT)
    val muted = Color(if (dark) DclPalette.MUTED else DclPalette.MUTED_LIGHT)
    val textColors = ButtonDefaults.textButtonColors(contentColor = accentText)

    ModalBottomSheet(onDismissRequest = { cancel() }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(MapsText.SHEET_TITLE, style = MaterialTheme.typography.titleLarge, color = accentText)
            Spacer(Modifier.height(12.dp))
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (showsRadius) {
                Spacer(Modifier.height(12.dp))
                Text(MapsText.STREETS_WITHIN, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                val choices = StreetAreaPlan.DETAIL_CHOICES
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    choices.forEachIndexed { i, km ->
                        SegmentedButton(
                            selected = plan.detailKm == km,
                            onClick = { plan = StreetAreaPlan(plan.latitude, plan.longitude, km) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = choices.size),
                            enabled = !downloading && !stopping,
                        ) { Text(MapsText.radius(km)) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(MapsText.sizeText(plan.estimatedBytes.toLong()), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text(MapsText.SOURCE, style = MaterialTheme.typography.bodySmall, color = muted)
            Text(MapsText.SERVER_LEARNS, style = MaterialTheme.typography.bodySmall, color = muted)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = muted)

            when (phase) {
                Phase.Ready -> {}
                Phase.Downloading -> {
                    Spacer(Modifier.height(16.dp))
                    Text(MapsText.DOWNLOADING, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    val (done, total) = progress
                    LinearProgressIndicator(
                        progress = { done.toFloat() / maxOf(total, 1) },
                        modifier = Modifier.fillMaxWidth(),
                        color = accent,
                    )
                }
                Phase.Failed -> {
                    Spacer(Modifier.height(16.dp))
                    Text(MapsText.FAILURE, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { start() }, enabled = !stopping, colors = textColors) {
                        Text(MapsText.TRY_AGAIN)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { cancel() }, enabled = !stopping, colors = textColors) { Text(MapsText.CANCEL) }
                if (phase == Phase.Ready) {
                    Button(
                        onClick = { start() },
                        enabled = !stopping,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
                    ) { Text(MapsText.DOWNLOAD) }
                }
            }
        }
    }
}
