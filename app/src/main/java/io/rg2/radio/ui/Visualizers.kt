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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
private val VISUALIZER_HEIGHT = 120.dp

/**
 * The visualizer pane shown in the program area when captions are off (or
 * unavailable). Offers both implementations behind a live selector so they can
 * be compared on-device; the reactive one requests RECORD_AUDIO on demand and
 * falls back to synthetic until granted.
 */
@Composable
fun VisualizerPane(
    reactive: Boolean,
    onReactiveChange: (Boolean) -> Unit,
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
        if (!granted) onReactiveChange(false) // denied → snap back to synthetic
    }

    LaunchedEffect(reactive) {
        if (reactive && !hasMic) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(VISUALIZER_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        if (reactive && hasMic) {
            ReactiveVisualizer(audioSessionId, Amber, Modifier.fillMaxWidth().height(VISUALIZER_HEIGHT))
        } else {
            SyntheticVisualizer(isPlaying, Amber, Modifier.fillMaxWidth().height(VISUALIZER_HEIGHT))
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VizModeChip("SYNTHETIC", active = !reactive) { onReactiveChange(false) }
        VizModeChip("REACTIVE", active = reactive) { onReactiveChange(true) }
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
    // Amplitude eases to near-zero when paused so the bars "settle".
    val amp by animateFloatAsState(if (isPlaying) 1f else 0.04f, tween(500), label = "amp")

    Canvas(modifier) {
        val values = FloatArray(BAR_COUNT) { i ->
            val f = i.toFloat() / BAR_COUNT
            val arch = sin(PI * f)                                   // 0 at edges, 1 in the middle
            val a = (sin((phase + f * 6f).toDouble()) + 1.0) / 2.0
            val b = (sin((phase * 1.7f + f * 11f).toDouble()) + 1.0) / 2.0
            (arch * (0.6 * a + 0.4 * b) * amp).toFloat()
        }
        drawSpectrum(values, color)
    }
}

// ---------------------------------------------------------------------------
// Reactive: real FFT from the audio output via the Visualizer effect.
// ---------------------------------------------------------------------------

@Composable
fun ReactiveVisualizer(sessionId: Int, color: Color, modifier: Modifier = Modifier) {
    var values by remember { mutableStateOf(FloatArray(BAR_COUNT)) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(sessionId) {
        if (sessionId <= 0) return@DisposableEffect onDispose {}
        val smoothed = FloatArray(BAR_COUNT)
        val visualizer = runCatching {
            Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, rate: Int) {}
                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                            fft ?: return
                            val bars = fftToBars(fft)
                            for (i in bars.indices) smoothed[i] = smoothed[i] * 0.55f + bars[i] * 0.45f
                            values = smoothed.copyOf()
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    /* waveform = */ false,
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
        Canvas(Modifier.fillMaxWidth().height(VISUALIZER_HEIGHT)) { drawSpectrum(values, color) }
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
// Shared bar rendering + FFT math.
// ---------------------------------------------------------------------------

private fun DrawScope.drawSpectrum(values: FloatArray, color: Color) {
    if (values.isEmpty()) return
    val n = values.size
    val gap = size.width * 0.010f
    val barW = (size.width - gap * (n - 1)) / n
    for (i in 0 until n) {
        val v = values[i].coerceIn(0f, 1f)
        val h = (0.04f + 0.96f * v) * size.height
        val x = i * (barW + gap)
        drawRoundRect(
            color = color,
            topLeft = Offset(x, size.height - h),
            size = Size(barW, h),
            cornerRadius = CornerRadius(barW * 0.5f, barW * 0.5f),
        )
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
