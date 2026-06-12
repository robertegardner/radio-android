package io.rg2.radio.audio

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.rg2.radio.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * "Duck on talk": when enabled, watches the playing stream through the shared
 * [AudioTapHub] FFT feed, classifies music vs talk ([TalkMusicClassifier]),
 * and ramps the player volume to a near-mute during talk/commercial stretches
 * with a fast ramp back up the moment music returns.
 *
 * Lives in PlaybackService (not the UI) so it keeps working with the screen
 * off. Only ever ducks the FM/AM radio stream — scanner items are talk the
 * user chose to hear. Needs RECORD_AUDIO (same grant as the reactive
 * visualizers); without it the tap fails and the status says so.
 */
class DuckController(
    private val player: Player,
    private val container: AppContainer,
    private val scope: CoroutineScope,
) : Player.Listener, AudioTapHub.Consumer {

    private var attached = false
    private var rampJob: Job? = null
    private var ducked = false

    private val classifier = TalkMusicClassifier { state, score -> apply(state, score) }

    init {
        player.addListener(this)
        scope.launch {
            combine(container.duckEnabled, container.audioSession) { e, s -> e to s }
                .collect { refresh() }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()

    /** AudioTapHub.Consumer — FFT frames feed the classifier. */
    override fun onFft(fft: ByteArray) = classifier.onFft(fft)

    fun release() {
        player.removeListener(this)
        detach(restoreVolume = false) // player is being torn down anyway
    }

    private fun isRadioItem(): Boolean =
        player.currentMediaItem?.mediaId
            ?.startsWith(io.rg2.radio.playback.PlaybackService.SCANNER_PREFIX) != true

    private fun refresh() {
        val want = container.duckEnabled.value &&
            container.audioSession.value > 0 &&
            player.isPlaying &&
            isRadioItem()
        when {
            want && !attached -> attach(container.audioSession.value)
            !want && attached -> detach(restoreVolume = true)
        }
        if (!want) container.duckStatus.value = DuckStatus(active = false)
    }

    private fun attach(sessionId: Int) {
        classifier.reset()
        attached = true
        val tapLive = container.audioTap.acquire(sessionId, this)
        container.duckStatus.value = if (tapLive) {
            DuckStatus(active = true, state = DuckState.MUSIC, score = 0f)
        } else {
            DuckStatus(active = false, note = "audio tap unavailable (mic permission?)")
        }
    }

    private fun detach(restoreVolume: Boolean) {
        if (attached) container.audioTap.release(this)
        attached = false
        ducked = false
        if (restoreVolume) rampTo(1f, UNDUCK_RAMP_MS)
    }

    /** Classifier verdict (tap callbacks land on the main looper). */
    private fun apply(state: DuckState, score: Float) {
        if (!attached) return
        container.duckStatus.value = DuckStatus(active = true, state = state, score = score)
        val wantDuck = state == DuckState.TALK
        if (wantDuck != ducked) {
            ducked = wantDuck
            if (wantDuck) rampTo(DUCK_VOLUME, DUCK_RAMP_MS) else rampTo(1f, UNDUCK_RAMP_MS)
        }
    }

    private fun rampTo(target: Float, durationMs: Long) {
        rampJob?.cancel()
        rampJob = scope.launch {
            val from = player.volume
            if (from == target) return@launch
            val steps = (durationMs / STEP_MS).coerceAtLeast(1)
            for (i in 1..steps) {
                player.volume = from + (target - from) * i / steps
                delay(STEP_MS)
            }
            player.volume = target
        }
    }

    private companion object {
        const val DUCK_VOLUME = 0.07f     // "nearly muted"
        const val DUCK_RAMP_MS = 1_200L   // easing down can be gentle
        const val UNDUCK_RAMP_MS = 250L   // music is back — get out of the way
        const val STEP_MS = 25L
    }
}
