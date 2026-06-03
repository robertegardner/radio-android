package io.rg2.radio

import android.app.Application
import io.rg2.radio.data.ArtworkRepository
import io.rg2.radio.data.InMemoryRadioSettings
import io.rg2.radio.data.NowPlayingRepository
import io.rg2.radio.data.RadioApi
import io.rg2.radio.data.RadioSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/**
 * Application entry point. Owns the app-wide singletons via [container]. No DI
 * framework — a hand-rolled container is enough at this size (house style:
 * minimal deps).
 */
class RadioApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer()
    }
}

/**
 * Shared singletons. [settings] is currently in-memory (default base URL, no
 * auth); it gets swapped for the encrypted store when the settings screen
 * lands. Everything reads settings through the `{ settings }` lambda so that
 * swap won't require rebuilding [api].
 */
class AppContainer {
    @Volatile
    var settings: RadioSettings = InMemoryRadioSettings()

    /** One OkHttp client (shared connection pool) for the backend and artwork lookups. */
    val httpClient: OkHttpClient = RadioApi.defaultClient()

    val api: RadioApi = RadioApi({ settings }, httpClient)

    val nowPlayingRepository: NowPlayingRepository = NowPlayingRepository(api)

    val artworkRepository: ArtworkRepository = ArtworkRepository(httpClient)

    /**
     * The active ExoPlayer audio session id, published by PlaybackService and
     * consumed by the reactive visualizer to attach a [android.media.audiofx.Visualizer].
     * 0 means "not yet known".
     */
    val audioSessionId: MutableStateFlow<Int> = MutableStateFlow(0)
    val audioSession: StateFlow<Int> get() = audioSessionId
}
