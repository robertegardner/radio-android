package io.rg2.radio.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Polls the backend's read endpoints, mirroring the web `/radio` UI's cadence:
 * `now_playing` ~1s, `stations` ~30s (see CLAUDE.md / docs/api.md).
 *
 * Each emission is a [Result] so the ViewModel can keep showing the last good
 * value through a transient network blip rather than tearing the UI down. The
 * flows are cold: collection drives the polling, so binding them to
 * `stateIn(WhileSubscribed)` automatically pauses polling when nothing is
 * observing (e.g. the app is backgrounded) — we don't hammer the Pi at 1 Hz
 * for a UI nobody's looking at.
 */
class NowPlayingRepository(private val api: RadioApi) {

    fun nowPlaying(periodMs: Long = NOW_PLAYING_PERIOD_MS): Flow<Result<NowPlaying>> =
        poll(periodMs) { api.nowPlaying() }

    fun stations(periodMs: Long = STATIONS_PERIOD_MS): Flow<Result<Stations>> =
        poll(periodMs) { api.stations() }

    /** Lightweight tune/bitrate status — drives the bitrate chips. */
    fun status(periodMs: Long = STATUS_PERIOD_MS): Flow<Result<Status>> =
        poll(periodMs) { api.status() }

    private fun <T> poll(periodMs: Long, fetch: suspend () -> T): Flow<Result<T>> = flow {
        while (currentCoroutineContext().isActive) {
            emit(runCatching { fetch() })
            delay(periodMs)
        }
    }

    companion object {
        const val NOW_PLAYING_PERIOD_MS = 1_000L
        const val STATIONS_PERIOD_MS = 30_000L
        const val STATUS_PERIOD_MS = 10_000L
    }
}
