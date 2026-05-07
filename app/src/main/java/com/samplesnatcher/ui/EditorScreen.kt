package com.samplesnatcher.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samplesnatcher.R
import com.samplesnatcher.audio.BpmEstimator
import com.samplesnatcher.audio.PcmRingBuffer
import com.samplesnatcher.audio.PreviewPlayer
import com.samplesnatcher.audio.SampleSnapping
import com.samplesnatcher.export.WavExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    ring: PcmRingBuffer,
    isCapturing: Boolean,
    bufferFrozen: Boolean = false,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var peaks by remember { mutableStateOf(FloatArray(0)) }
    var selStart by remember { mutableFloatStateOf(0.05f) }
    var selEnd by remember { mutableFloatStateOf(0.95f) }
    var viewportStart by remember { mutableFloatStateOf(0f) }
    var viewportEnd by remember { mutableFloatStateOf(1f) }
    var sliderRangeStart by remember { mutableFloatStateOf(0f) }
    var sliderRangeEnd by remember { mutableFloatStateOf(1f) }
    var zeroSnap by remember { mutableStateOf(true) }
    var transientSnap by remember { mutableStateOf(false) }
    var loopExport by remember { mutableStateOf(true) }
    var label by remember { mutableStateOf("loop") }
    var tapBpm by remember { mutableIntStateOf(0) }
    var tapTimes by remember { mutableStateOf(listOf<Long>()) }
    var previewLoop by remember { mutableStateOf(true) }
    val preview = remember(ring.sampleRate, ring.channelCount) {
        PreviewPlayer(ring.sampleRate, ring.channelCount)
    }
    val previewPlaying by preview.isPlaying.collectAsStateWithLifecycle(initialValue = false)
    val playheadSelFrac by preview.playheadFraction.collectAsStateWithLifecycle(initialValue = 0f)

    LaunchedEffect(previewPlaying) {
        if (!previewPlaying) return@LaunchedEffect
        while (isActive && preview.isPlaying.value) {
            preview.tickPlayhead()
            delay(16L)
        }
    }

    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<PendingWavExport?>(null) }
    val saveWavLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav"),
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri != null && payload != null) {
            scope.launch(Dispatchers.IO) {
                val ok = WavExport.writeWavToUri(
                    context = context,
                    uri = uri,
                    interleavedS16 = payload.pcm,
                    sampleRate = payload.sampleRate,
                    channelCount = payload.channelCount,
                )
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.exported_uri, uri.toString()),
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    LaunchedEffect(ring, isCapturing, bufferFrozen) {
        while (isActive) {
            peaks = try {
                withContext(Dispatchers.Default) {
                    ring.computePeakEnvelope(480)
                }
            } catch (_: Throwable) {
                FloatArray(480) { 0f }
            }
            delay(
                when {
                    bufferFrozen -> 1500L
                    isCapturing -> 250L
                    else -> 600L
                },
            )
        }
    }

    DisposableEffect(preview) {
        onDispose { preview.stop() }
    }

    fun safe01(v: Float): Float = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f

    fun framesFromSelection(): Pair<Long, Long> {
        val w = ring.getFrameWindow()
        val oldest = w.oldest
        val newest = w.newest
        if (newest <= oldest) return oldest to oldest
        val maxIndex = newest - 1
        val avail = w.available
        if (avail <= 1L) return oldest to oldest
        val lo = min(safe01(selStart), safe01(selEnd))
        val hi = max(safe01(selStart), safe01(selEnd))
        var s = (oldest + (lo * (avail - 1).toFloat()).toLong())
        var e = (oldest + (hi * (avail - 1).toFloat()).toLong())
        s = s.coerceAtLeast(oldest).coerceAtMost(maxIndex)
        e = e.coerceAtLeast(oldest).coerceAtMost(maxIndex)
        if (e <= s) {
            e = (s + 1).coerceAtMost(maxIndex)
        }
        if (e <= s) {
            s = (e - 1).coerceAtLeast(oldest)
        }
        if (e <= s) return oldest to oldest
        return s to e
    }

    fun applySnapping() {
        try {
            var (s, e) = framesFromSelection()
            // Onsets first so zero-cross doesn’t pull edges away from transients; omit zero step if off.
            if (transientSnap) {
                s = SampleSnapping.snapTransient(ring, ring.channelCount, s)
                e = SampleSnapping.snapTransient(ring, ring.channelCount, e)
            }
            if (zeroSnap) {
                s = SampleSnapping.snapZeroCrossing(ring, ring.channelCount, s)
                e = SampleSnapping.snapZeroCrossing(ring, ring.channelCount, e)
            }
            if (e <= s) return
            val w = ring.getFrameWindow()
            val avail = w.available
            if (avail <= 1) return
            val oldest = w.oldest
            val denom = (avail - 1).coerceAtLeast(1)
            selStart = safe01((s - oldest).toFloat() / denom)
            selEnd = safe01((e - oldest).toFloat() / denom)
        } catch (_: Throwable) {
            // Keep editor alive if snapping races with live buffer updates.
        }
    }

    fun sliderGlobalToLocal(global: Float): Float {
        val lo = min(sliderRangeStart, sliderRangeEnd).coerceIn(0f, 1f)
        val hi = max(sliderRangeStart, sliderRangeEnd).coerceIn(0f, 1f)
        val span = (hi - lo).coerceAtLeast(1e-6f)
        return safe01((global - lo) / span)
    }

    fun sliderLocalToGlobal(local: Float): Float {
        val lo = min(sliderRangeStart, sliderRangeEnd).coerceIn(0f, 1f)
        val hi = max(sliderRangeStart, sliderRangeEnd).coerceIn(0f, 1f)
        return (lo + (hi - lo) * local.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val selLo = min(safe01(selStart), safe01(selEnd))
            val selHi = max(safe01(selStart), safe01(selEnd))
            val playheadNorm =
                if (previewPlaying) selLo + (selHi - selLo) * playheadSelFrac else null
            WaveformCanvas(
                peaks = peaks,
                selectionStart = selLo,
                selectionEnd = selHi,
                playheadNormalized = playheadNorm,
                modifier = Modifier.fillMaxWidth(),
                zoomHintDescription = stringResource(R.string.waveform_zoom_hint),
                onViewportChanged = { start, end ->
                    viewportStart = start
                    viewportEnd = end
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.waveform_zoom_hint_short),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        val vLo = min(viewportStart, viewportEnd).coerceIn(0f, 1f)
                        val vHi = max(viewportStart, viewportEnd).coerceIn(0f, 1f)
                        sliderRangeStart = vLo
                        sliderRangeEnd = vHi
                        selStart = vLo
                        selEnd = vHi
                        applySnapping()
                    },
                ) {
                    Text(stringResource(R.string.use_zoom_for_selection))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = {
                        sliderRangeStart = 0f
                        sliderRangeEnd = 1f
                    },
                ) {
                    Text(stringResource(R.string.reset_slider_range))
                }
            }
            Text(stringResource(R.string.selection_start), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = sliderGlobalToLocal(safe01(selStart)),
                onValueChange = { selStart = safe01(sliderLocalToGlobal(it)) },
                onValueChangeFinished = { applySnapping() },
                valueRange = 0f..1f,
            )
            Text(stringResource(R.string.selection_end), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = sliderGlobalToLocal(safe01(selEnd)),
                onValueChange = { selEnd = safe01(sliderLocalToGlobal(it)) },
                onValueChangeFinished = { applySnapping() },
                valueRange = 0f..1f,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.snap_zero))
                Switch(checked = zeroSnap, onCheckedChange = { zeroSnap = it })
                Text(stringResource(R.string.snap_transient))
                Switch(checked = transientSnap, onCheckedChange = { transientSnap = it })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.loop_preview), style = MaterialTheme.typography.labelLarge)
                Switch(checked = previewLoop, onCheckedChange = { previewLoop = it })
                Button(
                    onClick = {
                        val (s, e) = framesFromSelection()
                        val pcm = ring.copyRangeInterleaved(s, e)
                        if (pcm.isEmpty()) {
                            Toast.makeText(context, R.string.preview_empty, Toast.LENGTH_SHORT).show()
                        } else {
                            val ok = preview.play(pcm, loop = previewLoop)
                            if (!ok) {
                                Toast.makeText(context, R.string.preview_init_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.preview_selection))
                }
                Button(
                    onClick = { preview.stop() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.stop_preview))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.export_loop_toggle))
                Switch(checked = loopExport, onCheckedChange = { loopExport = it })
            }
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.export_label_hint)) },
                supportingText = { Text(stringResource(R.string.export_label_body)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val t = SystemClock.uptimeMillis()
                        tapTimes = (tapTimes + t).takeLast(12)
                        val deltas = tapTimes.zipWithNext { a, b -> b - a }
                            .filter { it in 120L..2000L }
                        tapBpm = if (deltas.isNotEmpty()) {
                            (60000.0 / deltas.map { it.toDouble() }.average()).roundToInt()
                                .coerceIn(40, 300)
                        } else {
                            0
                        }
                    },
                ) {
                    Text(stringResource(R.string.tap_tempo))
                }
                Text(
                    "Tap BPM: ${if (tapBpm > 0) tapBpm else "—"}",
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            Button(
                onClick = {
                    val (s, e) = framesFromSelection()
                    val pcm = ring.copyRangeInterleaved(s, e)
                    if (pcm.isEmpty()) {
                        Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    preview.stop()
                    val est = BpmEstimator.estimate(pcm, ring.sampleRate, ring.channelCount)
                    val bpmForName: Int? = when {
                        tapBpm in 40..300 -> tapBpm
                        loopExport && est.bpm in 40..300 && est.confidence >= 0.35f -> est.bpm
                        else -> null
                    }
                    val suggested = WavExport.suggestedDisplayName(
                        userLabel = label,
                        includeBpmInName = bpmForName != null,
                        bpm = bpmForName,
                    )
                    pendingExport = PendingWavExport(
                        pcm = pcm,
                        sampleRate = ring.sampleRate,
                        channelCount = ring.channelCount,
                    )
                    saveWavLauncher.launch(suggested)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.export_wav))
            }
            if (ring.getFrameWindow().available == 0L) {
                Text(
                    "Buffer is empty — start capture and play audio on the device.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Holds PCM until the system save dialog returns a target [android.net.Uri]. */
private class PendingWavExport(
    val pcm: ShortArray,
    val sampleRate: Int,
    val channelCount: Int,
)
