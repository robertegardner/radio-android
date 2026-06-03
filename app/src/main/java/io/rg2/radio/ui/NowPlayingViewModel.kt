package io.rg2.radio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.rg2.radio.RadioApp
import io.rg2.radio.data.NowPlaying
import io.rg2.radio.data.Stations
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

/**
 * One emission of a polled endpoint. [data] holds the last successful value
 * (kept through transient errors so the UI doesn't flicker), and [error] is
 * non-null when the most recent poll failed.
 */
data class Polled<T>(
    val data: T? = null,
    val error: String? = null,
) {
    val hasData: Boolean get() = data != null
}

/**
 * Exposes the polled now-playing and station feeds as [StateFlow]s. Backed by
 * `stateIn(WhileSubscribed)`, so polling runs only while the UI is observing
 * and stops ~5s after it stops (e.g. on backgrounding) — playback continues in
 * the service regardless.
 *
 * Uses [AndroidViewModel] so the default factory can construct it; the shared
 * repository comes from the application container.
 */
class NowPlayingViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as RadioApp).container.nowPlayingRepository

    val nowPlaying: StateFlow<Polled<NowPlaying>> =
        repo.nowPlaying()
            .runningFold(Polled<NowPlaying>()) { prev, result -> prev.reduce(result) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Polled())

    val stations: StateFlow<Polled<Stations>> =
        repo.stations()
            .runningFold(Polled<Stations>()) { prev, result -> prev.reduce(result) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Polled())

    private fun <T> Polled<T>.reduce(result: Result<T>): Polled<T> = result.fold(
        onSuccess = { copy(data = it, error = null) },
        onFailure = { copy(error = it.message ?: "Network error") },
    )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
