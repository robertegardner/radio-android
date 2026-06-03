package io.rg2.radio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.rg2.radio.RadioApp
import io.rg2.radio.data.NowPlaying
import io.rg2.radio.data.Stations
import io.rg2.radio.data.coverArtUrl
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
     * Cover-art URL for the current track. Prefers art the backend already
     * fetched (now_playing.track.art_url); only falls back to the on-device
     * iTunes lookup when the backend provides none. Null for talk/unidentified.
     * Recomputes only when the track or its art changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val artworkUrl: StateFlow<String?> =
        nowPlaying
            .map { polled ->
                polled.data?.let { np ->
                    ArtRequest(np.coverArtUrl(), np.trackArtist(), np.trackTitle())
                }
            }
            .distinctUntilChanged()
            .mapLatest { req ->
                when {
                    req == null -> null
                    req.directUrl != null -> req.directUrl // backend already has it
                    req.artist != null && req.title != null ->
                        artworkRepo.artworkUrl(req.artist, req.title)
                    else -> null
                }
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

/**
 * Drives artwork resolution + change detection: the backend-provided art URL if
 * any, plus artist/title for the iTunes fallback. distinctUntilChanged on this
 * means we only re-resolve when the track or its art actually changes.
 */
private data class ArtRequest(
    val directUrl: String?,
    val artist: String?,
    val title: String?,
)
