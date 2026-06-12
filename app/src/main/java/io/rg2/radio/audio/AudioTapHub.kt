package io.rg2.radio.audio

import android.media.audiofx.Visualizer
import android.util.Log

/**
 * The ONE `Visualizer` on the player's audio session, fanned out to every
 * consumer (reactive viz canvas, MilkDrop PCM feed, duck classifier).
 *
 * Why a hub: multiple Visualizer instances on the same session are flaky
 * across devices — the second create can fail outright, which is exactly what
 * broke duck-on-talk while a viz style was on screen. No code outside this
 * class may construct a Visualizer.
 *
 * Threading: acquire/release are called on the main thread (composables and
 * the playback service both live there), and since the Visualizer is created
 * on the main thread its capture callbacks land on the main looper too — so
 * consumers run where they always did. Everything is @Synchronized anyway for
 * safety, and the callback iterates a snapshot.
 */
class AudioTapHub {

    interface Consumer {
        fun onWaveform(waveform: ByteArray) {}
        fun onFft(fft: ByteArray) {}
    }

    private val consumers = LinkedHashSet<Consumer>()
    private var visualizer: Visualizer? = null
    private var sessionId = 0

    /** Whether the tap is currently live (created and enabled). */
    @get:Synchronized
    val active: Boolean
        get() = visualizer != null

    /**
     * Register [consumer] against [sessionId]. Returns true if the shared tap
     * is live (possibly created just now); false if it can't be (no session,
     * RECORD_AUDIO missing, or the device refused the effect).
     */
    @Synchronized
    fun acquire(sessionId: Int, consumer: Consumer): Boolean {
        consumers.add(consumer)
        if (sessionId != this.sessionId || visualizer == null) rebuild(sessionId)
        return visualizer != null
    }

    @Synchronized
    fun release(consumer: Consumer) {
        consumers.remove(consumer)
        if (consumers.isEmpty()) teardown()
    }

    private fun rebuild(newSessionId: Int) {
        teardown()
        sessionId = newSessionId
        if (newSessionId <= 0 || consumers.isEmpty()) return
        visualizer = runCatching {
            Visualizer(newSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, rate: Int) {
                            w ?: return
                            snapshot().forEach { it.onWaveform(w) }
                        }

                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                            fft ?: return
                            snapshot().forEach { it.onFft(fft) }
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    /* waveform = */ true,
                    /* fft = */ true,
                )
                enabled = true
            }
        }.onFailure {
            Log.w(TAG, "audio tap unavailable on session $newSessionId", it)
        }.getOrNull()
    }

    @Synchronized
    private fun snapshot(): List<Consumer> = consumers.toList()

    private fun teardown() {
        visualizer?.let {
            runCatching { it.enabled = false }
            runCatching { it.release() }
        }
        visualizer = null
    }

    private companion object {
        const val TAG = "AudioTapHub"
    }
}
