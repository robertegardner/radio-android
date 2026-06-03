package io.rg2.radio.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.rg2.radio.ui.theme.Amber
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

private const val BAR_COUNT = 32
private val VISUALIZER_HEIGHT = 130.dp

/**
 * Visualizer styles, Winamp-flavored. All but [SYNTHETIC] are driven by the
 * real audio (FFT or waveform) and need RECORD_AUDIO.
 */
enum class VizStyle(val label: String, val reactive: Boolean) {
    BARS("BARS", true),
    PEAKS("PEAKS", true),
    MIRROR("MIRROR", true),
    SCOPE("SCOPE", true),
    SYNTHETIC("SYNTH", false),
}

/**
 * The visualizer pane shown in the program area when captions are off (or
 * unavailable). A reactive style requests RECORD_AUDIO on demand and falls back
 * to SYNTHETIC until granted.
 */
@Composable
fun VisualizerPane(
    style: VizStyle,
    onStyleChange: (VizStyle) -> Unit,
    isPlaying: Boolean,
    audioSessionId: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasMic by remember { mutableStateOf(hasRecordPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMic = granted
        if (!granted) onStyleChange(VizStyle.SYNTHETIC) // denied → safe fallback
    }

    LaunchedEffect(style) {
        if (style.reactive && !hasMic) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(VISUALIZER_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        val canvasMod = Modifier.fillMaxWidth().height(VISUALIZER_HEIGHT)
        if (style.reactive && hasMic) {
            ReactiveVisualizer(style, audioSessionId, Amber, canvasMod)
        } else {
            SyntheticVisualizer(isPlaying, Amber, canvasMod)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VizStyle.entries.forEach { s ->
            VizModeChip(s.label, active = s == style) { onStyleChange(s) }
        }
    }
}

@Composable
private fun VizModeChip(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) Amber else MaterialTheme.colorScheme.surface
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = fg, fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
// Synthetic: time-driven animated bars. No audio, no permissions.
// ---------------------------------------------------------------------------

@Composable
fun SyntheticVisualizer(isPlaying: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "viz")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val amp by animateFloatAsState(if (isPlaying) 1f else 0.04f, tween(500), label = "amp")

    Canvas(modifier) {
        val values = FloatArray(BAR_COUNT) { i ->
            val f = i.toFloat() / BAR_COUNT
            val arch = sin(PI * f)
            val a = (sin((phase + f * 6f).toDouble()) + 1.0) / 2.0
            val b = (sin((phase * 1.7f + f * 11f).toDouble()) + 1.0) / 2.0
            (arch * (0.6 * a + 0.4 * b) * amp).toFloat()
        }
        drawBars(values, color)
    }
}

// ---------------------------------------------------------------------------
// Reactive: real FFT + waveform from the Visualizer effect.
// ---------------------------------------------------------------------------

@Composable
fun ReactiveVisualizer(style: VizStyle, sessionId: Int, color: Color, modifier: Modifier = Modifier) {
    var bars by remember { mutableStateOf(FloatArray(BAR_COUNT)) }
    var peaks by remember { mutableStateOf(FloatArray(BAR_COUNT)) }
    var waveform by remember { mutableStateOf(FloatArray(0)) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(sessionId) {
        if (sessionId <= 0) return@DisposableEffect onDispose {}
        val smoothed = FloatArray(BAR_COUNT)
        val peakHold = FloatArray(BAR_COUNT)
        val visualizer = runCatching {
            Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, rate: Int) {
                            w ?: return
                            waveform = FloatArray(w.size) { ((w[it].toInt() and 0xFF) - 128) / 128f }
                        }

                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                            fft ?: return
                            val next = fftToBars(fft)
                            for (i in next.indices) {
                                smoothed[i] = smoothed[i] * 0.55f + next[i] * 0.45f
                                peakHold[i] = maxOf(peakHold[i] - 0.025f, smoothed[i]) // gravity
                            }
                            bars = smoothed.copyOf()
                            peaks = peakHold.copyOf()
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    /* waveform = */ true,
                    /* fft = */ true,
                )
                enabled = true
            }
        }.getOrElse {
            failed = true
            null
        }

        onDispose {
            visualizer?.let {
                runCatching { it.enabled = false }
                runCatching { it.release() }
            }
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(VISUALIZER_HEIGHT)) {
            when (style) {
                VizStyle.PEAKS -> { drawBars(bars, color); drawPeakCaps(peaks, color) }
                VizStyle.MIRROR -> drawMirror(bars, color)
                VizStyle.SCOPE -> drawScope(waveform, color)
                else -> drawBars(bars, color)
            }
        }
        when {
            sessionId <= 0 -> HintText("waiting for audio…")
            failed -> HintText("reactive visualizer unavailable on this device")
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
}

// ---------------------------------------------------------------------------
// Drawing styles.
// ---------------------------------------------------------------------------

private fun DrawScope.drawBars(values: FloatArray, color: Color) {
    if (values.isEmpty()) return
    val n = values.size
    val gap = size.width * 0.010f
    val barW = (size.width - gap * (n - 1)) / n
    for (i in 0 until n) {
        val h = (0.04f + 0.96f * values[i].coerceIn(0f, 1f)) * size.height
        val x = i * (barW + gap)
        drawRoundRect(
            color = color,
            topLeft = Offset(x, size.height - h),
            size = Size(barW, h),
            cornerRadius = CornerRadius(barW * 0.5f, barW * 0.5f),
        )
    }
}

private fun DrawScope.drawPeakCaps(peaks: FloatArray, color: Color) {
    if (peaks.isEmpty()) return
    val n = peaks.size
    val gap = size.width * 0.010f
    val barW = (size.width - gap * (n - 1)) / n
    val capH = size.height * 0.02f
    for (i in 0 until n) {
        val y = size.height - peaks[i].coerceIn(0f, 1f) * size.height
        drawRect(color, Offset(i * (barW + gap), y), Size(barW, capH))
    }
}

private fun DrawScope.drawMirror(values: FloatArray, color: Color) {
    if (values.isEmpty()) return
    val n = values.size
    val gap = size.width * 0.010f
    val barW = (size.width - gap * (n - 1)) / n
    val mid = size.height / 2f
    for (i in 0 until n) {
        val half = (0.02f + 0.48f * values[i].coerceIn(0f, 1f)) * size.height
        val x = i * (barW + gap)
        drawRoundRect(
            color = color,
            topLeft = Offset(x, mid - half),
            size = Size(barW, half * 2),
            cornerRadius = CornerRadius(barW * 0.5f, barW * 0.5f),
        )
    }
}

private fun DrawScope.drawScope(samples: FloatArray, color: Color) {
    if (samples.size < 2) return
    val mid = size.height / 2f
    val step = size.width / (samples.size - 1)
    var prevX = 0f
    var prevY = mid - samples[0] * mid * 0.9f
    for (i in 1 until samples.size) {
        val x = i * step
        val y = mid - samples[i] * mid * 0.9f
        drawLine(color, Offset(prevX, prevY), Offset(x, y), strokeWidth = 2.5f)
        prevX = x
        prevY = y
    }
}

private fun fftToBars(fft: ByteArray): FloatArray {
    val points = fft.size / 2
    val usable = (points * 0.7f).toInt().coerceAtLeast(BAR_COUNT)
    val per = (usable / BAR_COUNT).coerceAtLeast(1)
    val out = FloatArray(BAR_COUNT)
    val denom = ln(1f + 128f)
    for (b in 0 until BAR_COUNT) {
        var sum = 0f
        for (k in 0 until per) {
            val idx = b * per + k
            val re = idx * 2
            val im = idx * 2 + 1
            if (im < fft.size) {
                val r = fft[re].toInt().toFloat()
                val i = fft[im].toInt().toFloat()
                sum += sqrt(r * r + i * i)
            }
        }
        out[b] = (ln(1f + sum / per) / denom).coerceIn(0f, 1f)
    }
    return out
}

private fun hasRecordPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
