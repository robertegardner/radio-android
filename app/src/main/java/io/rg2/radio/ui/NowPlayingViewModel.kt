package io.rg2.radio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.rg2.radio.RadioApp
import io.rg2.radio.data.NowPlaying
import io.rg2.radio.data.Stations
import io.rg2.radio.data.trackArtist
import io.rg2.radio.data.trackTitle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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

    private val container = (app as RadioApp).container
    private val repo = container.nowPlayingRepository
    private val artworkRepo = container.artworkRepository

    val nowPlaying: StateFlow<Polled<NowPlaying>> =
        repo.nowPlaying()
            .runningFold(Polled<NowPlaying>()) { prev, result -> prev.reduce(result) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Polled())

    /**
     * Cover-art URL for the current track, or null when there's no song (talk
     * content) or no art was found. Refetches only when the track changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val artworkUrl: StateFlow<String?> =
        nowPlaying
            .map { it.data?.let { np -> SongKey(np.trackArtist(), np.trackTitle()) } }
            .distinctUntilChanged()
            .mapLatest { key ->
                val artist = key?.artist?.takeIf { it.isNotBlank() }
                val title = key?.title?.takeIf { it.isNotBlank() }
                if (artist != null && title != null) artworkRepo.artworkUrl(artist, title) else null
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

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

/** Identifies a track for artwork lookup / change detection. */
private data class SongKey(val artist: String?, val title: String?)
