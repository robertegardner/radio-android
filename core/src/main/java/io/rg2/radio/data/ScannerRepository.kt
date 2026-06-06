package io.rg2.radio.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Polls the scanner backend's status, mirroring the `/listen` page's live
 * status poll (current job + talkgroup + preemption detection). Like
 * [NowPlayingRepository], emissions are [Result] so the UI rides out transient
 * blips, and the flow is cold so binding it to `stateIn(WhileSubscribed)` pauses
 * polling when the scanner tab isn't on screen — we don't poll the Pi when
 * nobody's watching.
 */
class ScannerRepository(private val api: ScannerApi) {

    fun status(periodMs: Long = STATUS_PERIOD_MS): Flow<Result<ScannerStatus>> = flow {
        while (currentCoroutineContext().isActive) {
            emit(runCatching { api.status() })
            delay(periodMs)
        }
    }

    suspend fun calls(limit: Int = 15): Result<List<ScannerCall>> =
        runCatching { api.calls(limit) }

    companion object {
        // The web UI polls ~0.7-1s; 2s is plenty for talkgroup/preemption display
        // and gentler on the shared Pi.
        const val STATUS_PERIOD_MS = 2_000L
    }
}
