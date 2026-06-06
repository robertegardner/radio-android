package io.rg2.radio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.rg2.radio.RadioApp
import io.rg2.radio.data.AviationPreset
import io.rg2.radio.data.MoswinCategory
import io.rg2.radio.data.MonitorTuneRequest
import io.rg2.radio.data.ScannerCall
import io.rg2.radio.data.ScannerCatalog
import io.rg2.radio.data.ScannerSource
import io.rg2.radio.data.ScannerStatus
import io.rg2.radio.playback.PlaybackService
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

/**
 * Imperative UI state for the scanner page (what the operator has selected),
 * kept separate from the polled [ScannerStatus] (what the SDR is actually
 * doing). The screen renders a combination of the two.
 */
data class ScannerUiState(
    val source: ScannerSource = ScannerSource.MOSWIN,
    val category: MoswinCategory = ScannerCatalog.CATEGORIES.first(),
    val activePresetId: Int? = null,
    /** Aviation freq/label currently selected (for the LCD), or null. */
    val aviationFreq: String? = null,
    val aviationLabel: String? = null,
    val squelchOn: Boolean = false,
    /** A control switch/tune is in flight — guards against overlapping commands. */
    val busy: Boolean = false,
    val statusMessage: String = "",
)

/** A playback intent the VM hands to the screen, which drives the shared media session. */
sealed interface ScannerPlayback {
    data class Play(
        val mediaId: String,
        val url: String,
        val title: String,
        val subtitle: String?,
    ) : ScannerPlayback

    data object Stop : ScannerPlayback
}

/**
 * Drives the EMS/ATC scanner page. Switching source is asynchronous on the
 * backend (one shared SDR), so each control action POSTs, then polls
 * `/api/status` via [waitForJob] until the expected job is up, then emits a
 * [ScannerPlayback.Play] for the screen to route into the shared media session.
 *
 * [status] is a `WhileSubscribed` poll so it stops when the scanner tab is off
 * screen; imperative actions poll the API directly and are unaffected.
 */
class ScannerViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as RadioApp).container
    private val api = container.scannerApi
    private val repo = container.scannerRepository

    private val _ui = MutableStateFlow(ScannerUiState())
    val ui: StateFlow<ScannerUiState> = _ui.asStateFlow()

    private val _calls = MutableStateFlow<List<ScannerCall>>(emptyList())
    val calls: StateFlow<List<ScannerCall>> = _calls.asStateFlow()

    private val playbackChannel = Channel<ScannerPlayback>(Channel.BUFFERED)
    val playback: Flow<ScannerPlayback> = playbackChannel.receiveAsFlow()

    val status: StateFlow<Polled<ScannerStatus>> =
        repo.status()
            .runningFold(Polled<ScannerStatus>()) { prev, result ->
                result.fold(
                    onSuccess = { prev.copy(data = it, error = null) },
                    onFailure = { prev.copy(error = it.message ?: "Network error") },
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Polled())

    // ---- source switching ------------------------------------------------

    fun selectMoswin() {
        if (_ui.value.busy) return
        _ui.update {
            it.copy(source = ScannerSource.MOSWIN, activePresetId = null, busy = true,
                statusMessage = "Switching to MOSWIN P25…")
        }
        viewModelScope.launch {
            val resp = runCatching { api.selectMoswin() }.getOrElse {
                fail("Error: ${it.message}"); return@launch
            }
            if (resp.error != null) { fail("MOSWIN error: ${resp.error}"); return@launch }
            _ui.update { it.copy(statusMessage = "Waiting for SDRTrunk to lock control channel…") }
            val ok = waitForJob(ScannerCatalog.JOB_MOSWIN)
            if (ok) {
                _ui.update { it.copy(busy = false, statusMessage = "") }
                playCurrentMoswin()
            } else {
                fail("MOSWIN did not start — check scheduler")
            }
        }
    }

    /** Aviation is on-demand: just present the panel; tuning happens on preset/direct tune. */
    fun selectAviationSource() {
        if (_ui.value.busy) return
        _ui.update {
            it.copy(source = ScannerSource.AVIATION, statusMessage = "Pick a preset to tune the air band.")
        }
        playbackChannel.trySend(ScannerPlayback.Stop)
    }

    fun tunePreset(preset: AviationPreset) =
        tuneAviation(preset.freq, preset.gain, preset.label, preset.id)

    fun commitDirectTune(freqInput: String, gain: Int) {
        val freq = freqInput.trim()
        if (freq.isEmpty()) return
        tuneAviation(freq, gain, freq, presetId = null)
    }

    private fun tuneAviation(freq: String, gain: Int, label: String, presetId: Int?) {
        if (_ui.value.busy) return
        _ui.update {
            it.copy(source = ScannerSource.AVIATION, activePresetId = presetId, busy = true,
                aviationFreq = freq, aviationLabel = label, statusMessage = "Tuning $freq AM…")
        }
        viewModelScope.launch {
            val req = MonitorTuneRequest(
                freq = freq, gain = gain, label = label, audioSquelch = _ui.value.squelchOn,
            )
            val resp = runCatching { api.tuneMonitor(req) }.getOrElse {
                fail("Error: ${it.message}"); return@launch
            }
            if (resp.error != null) { fail("Tune error: ${resp.error}"); return@launch }
            _ui.update { it.copy(statusMessage = "Waiting for stream…") }
            val ok = waitForJob(ScannerCatalog.JOB_MONITOR)
            if (ok) {
                _ui.update { it.copy(busy = false, statusMessage = "") }
                playbackChannel.trySend(
                    ScannerPlayback.Play(
                        mediaId = PlaybackService.SCANNER_PREFIX + "av:" + freq,
                        url = ScannerCatalog.MONITOR_URL,
                        title = label,
                        subtitle = "Aviation AM · $freq",
                    ),
                )
            } else {
                fail("Tuner didn't start")
            }
        }
    }

    // ---- MOSWIN categories ----------------------------------------------

    /** Re-point the player at a category sub-stream; no backend call (same SDRTrunk). */
    fun selectCategory(category: MoswinCategory) {
        _ui.update { it.copy(category = category) }
        if (_ui.value.source == ScannerSource.MOSWIN) playCurrentMoswin()
    }

    private fun playCurrentMoswin() {
        val cat = _ui.value.category
        playbackChannel.trySend(
            ScannerPlayback.Play(
                mediaId = PlaybackService.SCANNER_PREFIX + "moswin:" + cat.slug,
                url = cat.url,
                title = "MOSWIN P25 · ${cat.name}",
                subtitle = "Cape County · 769.169 MHz",
            ),
        )
    }

    // ---- squelch ---------------------------------------------------------

    /** Pull the backend's persisted squelch state so the toggle reflects reality. */
    fun refreshSquelchState() {
        viewModelScope.launch {
            val state = runCatching { api.squelchState() }.getOrNull() ?: return@launch
            _ui.update { it.copy(squelchOn = state.enabled) }
        }
    }

    fun toggleSquelch() {
        val next = !_ui.value.squelchOn
        // Optimistic flip + warn about the pipeline-restart gap.
        _ui.update {
            it.copy(squelchOn = next, statusMessage = "Squelch ${if (next) "ON" else "OFF"} — restarting audio…")
        }
        viewModelScope.launch {
            val state = runCatching { api.setSquelch(next) }.getOrNull()
            if (state == null) {
                _ui.update { it.copy(statusMessage = "Squelch toggle failed") }
                return@launch
            }
            // Trust the state the backend echoed back (it owns the default).
            _ui.update { it.copy(squelchOn = state.enabled) }
            delay(SQUELCH_RESTART_MS) // keep the "restarting" note up for the gap
            _ui.update { cur -> if (cur.statusMessage.startsWith("Squelch")) cur.copy(statusMessage = "") else cur }
        }
    }

    // ---- recent calls ----------------------------------------------------

    fun refreshCalls() {
        viewModelScope.launch {
            repo.calls(CALLS_LIMIT).onSuccess { _calls.value = it }
        }
    }

    fun playCall(call: ScannerCall) {
        val url = api.recordingUrl(call) ?: return
        playbackChannel.trySend(
            ScannerPlayback.Play(
                mediaId = PlaybackService.SCANNER_PREFIX + "call:" + (call.path ?: call.filename),
                url = url,
                title = call.talkgroup ?: "MOSWIN call",
                subtitle = listOfNotNull(call.radio?.let { "← $it" }, call.ts).joinToString(" · "),
            ),
        )
    }

    // ---- helpers ---------------------------------------------------------

    /** Poll status until [jobName] holds the SDR, then let Icecast buffer. */
    private suspend fun waitForJob(jobName: String, maxMs: Long = 22_000, pollMs: Long = 700): Boolean {
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            delay(pollMs)
            val cur = runCatching { api.status() }.getOrNull()?.current
            if (cur?.name == jobName) {
                delay(BUFFER_MS) // let the Icecast mount fill before we connect
                return true
            }
        }
        return false
    }

    private fun fail(message: String) {
        _ui.update { it.copy(busy = false, statusMessage = message) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val BUFFER_MS = 3_000L
        private const val SQUELCH_RESTART_MS = 2_500L
        private const val CALLS_LIMIT = 15
    }
}
