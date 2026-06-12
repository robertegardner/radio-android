package io.rg2.radio.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class DuckState { MUSIC, TALK }

/**
 * What the duck feature is doing right now, published for the UI. [score] is
 * the latest music-likelihood (0 = talk, 1 = music) so the heuristic can be
 * observed and tuned on air.
 */
data class DuckStatus(
    val active: Boolean = false,
    val state: DuckState = DuckState.MUSIC,
    val score: Float = 0f,
)

/**
 * Music-vs-talk discrimination from the `Visualizer` FFT tap (8-bit magnitude
 * pairs, ~20 captures/s). Entirely heuristic — three features that survive FM
 * broadcast compression reasonably well:
 *
 *  - **beat**: normalized autocorrelation peak of the bass-band envelope over
 *    beat-period lags (0.25–0.8 s). Music with rhythm scores high; speech ~0.
 *  - **high-frequency sustain**: mean high-band/total energy ratio. Music
 *    (cymbals, synths, production sheen) keeps HF populated; speech HF comes
 *    only in sibilant bursts.
 *  - **envelope steadiness**: coefficient of variation of the speech-band
 *    envelope. Syllabic gating makes speech bursty; mastered music is steady.
 *
 * An asymmetric hysteresis state machine sits on top: switching to TALK takes
 * ~5 s of sustained low score (song outros mustn't duck), switching back to
 * MUSIC takes ~1.5 s (resumption should feel prompt).
 *
 * Known blind spot, by design: commercials with full music beds classify as
 * music. The signals to fix that (Whisper captions mode, track-ID state) live
 * in `/api/now_playing` and can be fused in later.
 */
class TalkMusicClassifier(private val onStatus: (DuckState, Float) -> Unit) {

    private val bass = FloatArray(CAPACITY)
    private val mid = FloatArray(CAPACITY)
    private val high = FloatArray(CAPACITY)
    private val total = FloatArray(CAPACITY)
    private var count = 0
    private var head = 0 // next write position

    private var framesSinceEval = 0
    private var framePeriodMs = 50f // EMA of inter-frame spacing (~20 Hz nominal)
    private var lastFrameAtMs = 0L
    private var lastEvalAtMs = 0L

    private var state = DuckState.MUSIC
    private var musicRunMs = 0L
    private var talkRunMs = 0L

    fun reset() {
        count = 0
        head = 0
        framesSinceEval = 0
        lastFrameAtMs = 0L
        lastEvalAtMs = 0L
        state = DuckState.MUSIC
        musicRunMs = 0L
        talkRunMs = 0L
    }

    /** Feed one Visualizer FFT capture. Caller's thread; no internal locking. */
    fun onFft(fft: ByteArray) {
        val now = System.nanoTime() / 1_000_000
        if (lastFrameAtMs != 0L) {
            val dt = (now - lastFrameAtMs).coerceIn(10, 250).toFloat()
            framePeriodMs = framePeriodMs * 0.95f + dt * 0.05f
        }
        lastFrameAtMs = now

        var b = 0f
        var m = 0f
        var h = 0f
        // fft[2k]/fft[2k+1] are the real/imag pairs; ~43 Hz per bin at 44.1 kHz.
        fun mag(bin: Int): Float {
            val re = fft[bin * 2].toInt().toFloat()
            val im = fft[bin * 2 + 1].toInt().toFloat()
            return sqrt(re * re + im * im)
        }
        val bins = fft.size / 2
        for (k in 1..min(4, bins - 1)) b += mag(k)            // ~43–200 Hz
        for (k in 7..min(78, bins - 1)) m += mag(k)           // ~300–3400 Hz
        for (k in 93..min(250, bins - 1)) h += mag(k)         // ~4–10.8 kHz

        bass[head] = b
        mid[head] = m
        high[head] = h
        total[head] = b + m + h
        head = (head + 1) % CAPACITY
        if (count < CAPACITY) count++

        if (++framesSinceEval >= EVAL_EVERY_FRAMES && count >= MIN_FRAMES) {
            framesSinceEval = 0
            evaluate(now)
        }
    }

    private fun evaluate(nowMs: Long) {
        val n = count
        // Reconstruct oldest→newest views for the windowed features.
        val start = (head - n + CAPACITY) % CAPACITY
        fun at(buf: FloatArray, i: Int) = buf[(start + i) % CAPACITY]

        // -- Feature 1: bass-envelope beat periodicity ------------------------
        var meanBass = 0f
        for (i in 0 until n) meanBass += at(bass, i)
        meanBass /= n
        var r0 = 1e-6f
        for (i in 0 until n) {
            val x = at(bass, i) - meanBass
            r0 += x * x
        }
        val minLag = max(1, (250f / framePeriodMs).toInt())   // 0.25 s
        val maxLag = min(n - 8, (800f / framePeriodMs).toInt()) // 0.8 s
        var beat = 0f
        var lag = minLag
        while (lag <= maxLag) {
            var r = 0f
            for (i in 0 until n - lag) {
                r += (at(bass, i) - meanBass) * (at(bass, i + lag) - meanBass)
            }
            beat = max(beat, r / r0)
            lag++
        }
        beat = beat.coerceIn(0f, 1f)

        // -- Feature 2: sustained high-frequency content ----------------------
        var hfRatio = 0f
        for (i in 0 until n) {
            val t = at(total, i)
            if (t > 1f) hfRatio += at(high, i) / t
        }
        hfRatio /= n
        val hfScore = (hfRatio / HF_FULL_SCALE).coerceIn(0f, 1f)

        // -- Feature 3: speech-band envelope steadiness -----------------------
        var meanMid = 0f
        for (i in 0 until n) meanMid += at(mid, i)
        meanMid /= n
        var varMid = 0f
        for (i in 0 until n) {
            val d = at(mid, i) - meanMid
            varMid += d * d
        }
        val cv = if (meanMid > 1f) sqrt(varMid / n) / meanMid else 1f
        val steady = (1f - cv / CV_FULL_SCALE).coerceIn(0f, 1f)

        val score = (W_BEAT * beat + W_HF * hfScore + W_STEADY * steady).coerceIn(0f, 1f)

        // -- Hysteresis: slow to duck, prompt to resume -----------------------
        val dt = if (lastEvalAtMs == 0L) 0L else (nowMs - lastEvalAtMs).coerceIn(0, 2_000)
        lastEvalAtMs = nowMs
        musicRunMs = if (score >= MUSIC_THRESHOLD) musicRunMs + dt else 0L
        talkRunMs = if (score <= TALK_THRESHOLD) talkRunMs + dt else 0L
        if (state == DuckState.TALK && musicRunMs >= MUSIC_HOLD_MS) state = DuckState.MUSIC
        if (state == DuckState.MUSIC && talkRunMs >= TALK_HOLD_MS) state = DuckState.TALK

        onStatus(state, score)
    }

    private companion object {
        const val CAPACITY = 256          // ~12 s of frames at 20 Hz
        const val MIN_FRAMES = 80         // ~4 s before the first verdict
        const val EVAL_EVERY_FRAMES = 8   // re-score ~2.5×/s

        // Feature weights / scales — the tuning surface. Score readout is in
        // the UI when DUCK is on; adjust against real broadcast content.
        const val W_BEAT = 0.50f
        const val W_HF = 0.30f
        const val W_STEADY = 0.20f
        const val HF_FULL_SCALE = 0.12f   // hfRatio at which music confidence saturates
        const val CV_FULL_SCALE = 0.60f   // envelope CV at which "bursty = speech" saturates

        const val MUSIC_THRESHOLD = 0.55f
        const val TALK_THRESHOLD = 0.42f
        const val MUSIC_HOLD_MS = 1_500L  // prompt resumption
        const val TALK_HOLD_MS = 5_000L   // don't duck song outros
    }
}
