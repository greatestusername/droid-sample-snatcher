package com.samplesnatcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Waveform strip with pinch-zoom and pan along the buffer **display only**.
 * Selection sliders remain normalized to the full buffer (0…1); zoom does not change selection math.
 */
@Composable
fun WaveformCanvas(
    peaks: FloatArray,
    selectionStart: Float,
    selectionEnd: Float,
    /** Full-buffer normalized 0…1; shown while preview is advancing through the selection. */
    playheadNormalized: Float? = null,
    modifier: Modifier = Modifier,
    zoomHintDescription: String = "",
) {
    var viewStart by remember { mutableFloatStateOf(0f) }
    var viewFrac by remember { mutableFloatStateOf(1f) }

    val minFrac = 1f / 256f

    val zoomMod = if (zoomHintDescription.isNotBlank()) {
        Modifier.semantics { contentDescription = zoomHintDescription }
    } else {
        Modifier
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .then(zoomMod),
    ) {
        val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(wPx, hPx) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (abs(zoom - 1f) > 1e-3f) {
                            val tFocus = viewStart + (centroid.x / wPx) * viewFrac
                            viewFrac = (viewFrac / zoom).coerceIn(minFrac, 1f)
                            viewStart = tFocus - (centroid.x / wPx) * viewFrac
                        }
                        if (viewFrac >= 1f - 1e-5f) {
                            viewFrac = 1f
                            viewStart = 0f
                        } else {
                            viewStart -= pan.x / wPx * viewFrac
                            viewStart = viewStart.coerceIn(0f, (1f - viewFrac).coerceAtLeast(0f))
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val mid = h / 2f
            val vs = viewStart.coerceIn(0f, (1f - viewFrac).coerceAtLeast(0f))
            val vf = viewFrac.coerceIn(minFrac, 1f)

            fun normXToScreen(t: Float): Float = ((t - vs) / vf) * w

            clipRect(0f, 0f, w, h) {
                val r0 = selectionStart.coerceIn(0f, 1f)
                val r1 = selectionEnd.coerceIn(0f, 1f)
                val a = min(r0, r1)
                val b = max(r0, r1)
                val selX0 = normXToScreen(a)
                val selX1 = normXToScreen(b)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0x331565C0), Color(0x22000000)),
                    ),
                    topLeft = Offset(selX0, 0f),
                    size = Size((selX1 - selX0).coerceAtLeast(1f), h),
                )

                val n = peaks.size
                if (n > 0) {
                    for (i in peaks.indices) {
                        val t0 = i.toFloat() / n
                        val t1 = (i + 1).toFloat() / n
                        if (t1 <= vs || t0 >= vs + vf) continue
                        val amp = peaks[i].coerceIn(0f, 1f)
                        val x0 = normXToScreen(t0)
                        val x1 = normXToScreen(t1)
                        val barWDraw = (x1 - x0).coerceAtLeast(1f)
                        val bh = amp * (h * 0.45f)
                        drawRoundRect(
                            color = Color(0xFF7CC9FF),
                            topLeft = Offset(x0 + 1f, mid - bh),
                            size = Size(maxOf(2f, barWDraw - 2f), bh * 2f),
                            cornerRadius = CornerRadius(2f, 2f),
                        )
                    }
                }

                val sxA = normXToScreen(a)
                val sxB = normXToScreen(b)
                drawLine(
                    color = Color(0x66FFFFFF),
                    start = Offset(sxA, 0f),
                    end = Offset(sxA, h),
                    strokeWidth = 3f,
                )
                drawLine(
                    color = Color(0x66FFFFFF),
                    start = Offset(sxB, 0f),
                    end = Offset(sxB, h),
                    strokeWidth = 3f,
                )

                val ph = playheadNormalized
                if (ph != null) {
                    val tPh = ph.coerceIn(0f, 1f)
                    val xPh = normXToScreen(tPh)
                    if (xPh in -4f..w + 4f) {
                        drawLine(
                            color = Color(0xFFFFB74D),
                            start = Offset(xPh, 0f),
                            end = Offset(xPh, h),
                            strokeWidth = 4f,
                        )
                    }
                }
            }
        }
    }
}
