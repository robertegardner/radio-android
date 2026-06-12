package io.rg2.radio.viz

import android.content.Context
import android.media.audiofx.Visualizer
import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.random.Random

private const val PRESET_ADVANCE_MS = 30_000L
private const val PRESET_ASSET_DIR = "milkdrop"

/**
 * MilkDrop-style visualizer: libprojectM rendering into a [GLSurfaceView],
 * fed mono PCM from the same `Visualizer` audio-session tap the other
 * reactive styles use. Counterpart of the backend web UI's Butterchurn
 * visualizer (see docs/web-visualizer.md) — same UX: random preset on start,
 * 30 s auto-advance with a soft blend, tap for the next preset immediately.
 */
@Composable
fun MilkdropVisualizer(sessionId: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var presets by remember { mutableStateOf<List<String>>(emptyList()) }

    // One-time asset extraction (projectM reads presets from real file paths).
    LaunchedEffect(Unit) { presets = extractPresets(context) }

    if (presets.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "loading presets…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        return
    }

    val view = remember(presets) { MilkdropGLView(context, presets) }

    // PCM tap: waveform-only Visualizer on the player's audio session. The
    // 8-bit mono waveform is coarse but plenty for beat/wave drivers.
    DisposableEffect(view, sessionId) {
        if (sessionId <= 0) return@DisposableEffect onDispose {}
        val tap = runCatching {
            Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, rate: Int) {
                            w ?: return
                            val pcm = FloatArray(w.size) { ((w[it].toInt() and 0xFF) - 128) / 128f }
                            view.feedPcm(pcm)
                        }

                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) = Unit
                    },
                    Visualizer.getMaxCaptureRate(),
                    /* waveform = */ true,
                    /* fft = */ false,
                )
                enabled = true
            }
        }.getOrNull()
        onDispose {
            tap?.let {
                runCatching { it.enabled = false }
                runCatching { it.release() }
            }
        }
    }

    // Auto-advance presets with a soft blend, like the web visualizer.
    LaunchedEffect(view) {
        while (true) {
            delay(PRESET_ADVANCE_MS)
            view.nextPreset(smooth = true)
        }
    }

    DisposableEffect(view) {
        view.onResume()
        onDispose {
            view.onPause()
            view.shutdown()
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { view.apply { setOnClickListener { nextPreset(smooth = false) } } },
        modifier = modifier.fillMaxSize(),
    )
}

/** Copy bundled presets out of assets so projectM can read them as files. */
private suspend fun extractPresets(context: Context): List<String> =
    withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, PRESET_ASSET_DIR)
        dir.mkdirs()
        val names = context.assets.list(PRESET_ASSET_DIR).orEmpty().filter { it.endsWith(".milk") }
        names.map { name ->
            val out = File(dir, name)
            if (!out.exists() || out.length() == 0L) {
                context.assets.open("$PRESET_ASSET_DIR/$name").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            out.absolutePath
        }.sorted()
    }

/**
 * GLES3 surface driving projectM. All projectM calls are funneled to the GL
 * thread (renderer callbacks / queueEvent) — the native side is not
 * thread-safe.
 */
private class MilkdropGLView(context: Context, presets: List<String>) : GLSurfaceView(context) {
    private val renderer = MilkdropRenderer(presets)

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun feedPcm(samples: FloatArray) = renderer.feedPcm(samples)

    fun nextPreset(smooth: Boolean) = queueEvent { renderer.nextPreset(smooth) }

    fun shutdown() = queueEvent { renderer.shutdown() }
}

private class MilkdropRenderer(private val presets: List<String>) : GLSurfaceView.Renderer {
    private var handle = 0L
    private var presetIdx = Random.nextInt(presets.size.coerceAtLeast(1))

    // Visualizer capture thread → GL thread. Bounded so a stalled GL thread
    // can't pile up buffers.
    private val pcmQueue = ConcurrentLinkedQueue<FloatArray>()

    fun feedPcm(samples: FloatArray) {
        if (pcmQueue.size < 8) pcmQueue.add(samples)
    }

    // GL thread only.
    fun nextPreset(smooth: Boolean) {
        if (handle == 0L || presets.isEmpty()) return
        presetIdx = if (presets.size > 1) {
            (presetIdx + 1 + Random.nextInt(presets.size - 1)) % presets.size
        } else {
            0
        }
        ProjectMNative.loadPreset(handle, presets[presetIdx], smooth)
    }

    // GL thread only.
    fun shutdown() {
        if (handle != 0L) {
            ProjectMNative.destroy(handle)
            handle = 0L
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // On a real context loss the old GL objects died with the context;
        // destroy frees the CPU side before we build a fresh instance. The
        // real surface size arrives in onSurfaceChanged right after.
        shutdown()
        handle = ProjectMNative.create(16, 16)
        if (handle != 0L && presets.isNotEmpty()) {
            ProjectMNative.loadPreset(handle, presets[presetIdx], false)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (handle != 0L) ProjectMNative.resize(handle, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (handle == 0L) return
        while (true) {
            val pcm = pcmQueue.poll() ?: break
            ProjectMNative.addPcm(handle, pcm)
        }
        ProjectMNative.renderFrame(handle)
    }
}
