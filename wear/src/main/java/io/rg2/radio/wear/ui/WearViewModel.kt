package io.rg2.radio.wear.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.rg2.radio.data.AviationPreset
import io.rg2.radio.data.Favorite
import io.rg2.radio.data.MoswinCategory
import io.rg2.radio.data.MonitorTuneRequest
import io.rg2.radio.data.NowPlaying
import io.rg2.radio.data.ScannerCatalog
import io.rg2.radio.data.ScannerStatus
import io.rg2.radio.wear.WearApp
import io.rg2.radio.wear.playback.WearPlaybackService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the watch is doing right now (for chip highlight + status line). */
data class WearUiState(
    val busy: Boolean = false,
    val message: String = "",
    /** mediaId of the last-selected source, for highlighting its chip. */
    val activeMediaId: String? = null,
)

/** A playback intent handed to the screen, which drives the watch's media session. */
sealed interface WearPlayback {
    /** A radio favorite — the service POSTs /api/tune and plays the FM stream. */
    data class PlayFavorite(val favorite: Favorite) : WearPlayback
    /** A resolved scanner stream — URL goes in requestMetadata.mediaUri. */
    data class PlayStream(val mediaId: String, val url: String, val title: String) : WearPlayback
}

/**
 * Drives the standalone watch app. Radio favorites tune+play via the service;
 * scanner sources need an async backend switch (one shared SDR), so those POST
 * then poll `/api/status` until the job is up before emitting a play intent —
 * the same pattern as the phone's scanner page, condensed for the watch.
 */
class WearViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as WearApp).container
    private val api = container.api
    private val scannerApi = container.scannerApi
    private val nowPlayingRepo = container.nowPlayingRepository
    private val scannerRepo = container.scannerRepository

    private val _ui = MutableStateFlow(WearUiState())
    val ui: StateFlow<WearUiState> = _ui.asStateFlow()

    private val playbackChannel = Channel<WearPlayback>(Channel.BUFFERED)
    val playback: Flow<WearPlayback> = playbackChannel.receiveAsFlow()

    /** Live now-playing (radio), kept through transient errors. Slow poll to spare the watch battery. */
    val nowPlaying: StateFlow<NowPlaying?> =
        nowPlayingRepo.nowPlaying(POLL_MS)
            .runningFold<Result<NowPlaying>, NowPlaying?>(null) { last, r -> r.getOrNull() ?: last }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    val scannerStatus: StateFlow<ScannerStatus?> =
        scannerRepo.status(POLL_MS)
            .runningFold<Result<ScannerStatus>, ScannerStatus?>(null) { last, r -> r.getOrNull() ?: last }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    // ---- radio -----------------------------------------------------------

    fun playFavorite(fav: Favorite) {
        _ui.update { it.copy(activeMediaId = fav.mediaId, message = "") }
        playbackChannel.trySend(WearPlayback.PlayFavorite(fav))
    }

    // ---- scanner ---------------------------------------------------------

    fun selectMoswinCategory(category: MoswinCategory) {
        if (_ui.value.busy) return
        val mediaId = WearPlaybackService.SCANNER_PREFIX + "moswin:" + category.slug
        viewModelScope.launch {
            // Switch the SDR to MOSWIN only if it isn't already there.
            if (scannerStatus.value?.current?.name != ScannerCatalog.JOB_MOSWIN) {
                _ui.update { it.copy(busy = true, message = "Starting MOSWIN…") }
                val resp = runCatching { scannerApi.selectMoswin() }.getOrNull()
                if (resp?.error != null || resp == null) { fail("MOSWIN failed"); return@launch }
                if (!waitForJob(ScannerCatalog.JOB_MOSWIN)) { fail("MOSWIN didn't start"); return@launch }
            }
            _ui.update { it.copy(busy = false, message = "", activeMediaId = mediaId) }
            playbackChannel.trySend(WearPlayback.PlayStream(mediaId, category.url, "MOSWIN ${category.name}"))
        }
    }

    fun tunePreset(preset: AviationPreset) {
        if (_ui.value.busy) return
        val mediaId = WearPlaybackService.SCANNER_PREFIX + "av:" + preset.freq
        _ui.update { it.copy(busy = true, message = "Tuning ${preset.label}…") }
        viewModelScope.launch {
            val req = MonitorTuneRequest(freq = preset.freq, gain = preset.gain, label = preset.label)
            val resp = runCatching { scannerApi.tuneMonitor(req) }.getOrNull()
            if (resp?.error != null || resp == null) { fail("Tune failed"); return@launch }
            if (!waitForJob(ScannerCatalog.JOB_MONITOR)) { fail("Tuner didn't start"); return@launch }
            _ui.update { it.copy(busy = false, message = "", activeMediaId = mediaId) }
            playbackChannel.trySend(WearPlayback.PlayStream(mediaId, ScannerCatalog.MONITOR_URL, preset.label))
        }
    }

    private suspend fun waitForJob(jobName: String, maxMs: Long = 22_000, pollMs: Long = 700): Boolean {
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            delay(pollMs)
            val cur = runCatching { scannerApi.status() }.getOrNull()?.current
            if (cur?.name == jobName) {
                delay(BUFFER_MS)
                return true
            }
        }
        return false
    }

    private fun fail(message: String) {
        _ui.update { it.copy(busy = false, message = message) }
    }

    companion object {
        private const val POLL_MS = 3_000L
        private const val STOP_MS = 5_000L
        private const val BUFFER_MS = 3_000L
    }
}
