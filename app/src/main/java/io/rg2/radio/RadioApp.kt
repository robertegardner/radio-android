package io.rg2.radio

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import io.rg2.radio.audio.DuckStatus
import io.rg2.radio.data.ArtworkRepository
import io.rg2.radio.data.InMemoryRadioSettings
import io.rg2.radio.data.NowPlayingRepository
import io.rg2.radio.data.RadioApi
import io.rg2.radio.data.RadioSettings
import io.rg2.radio.data.ScannerApi
import io.rg2.radio.data.ScannerRepository
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
        container = AppContainer(getSharedPreferences("radio", Context.MODE_PRIVATE))
    }
}

/**
 * Shared singletons. [settings] is currently in-memory (default base URL, no
 * auth); it gets swapped for the encrypted store when the settings screen
 * lands. Everything reads settings through the `{ settings }` lambda so that
 * swap won't require rebuilding [api].
 */
class AppContainer(private val prefs: SharedPreferences) {
    @Volatile
    var settings: RadioSettings = InMemoryRadioSettings()

    /** One OkHttp client (shared connection pool) for the backend and artwork lookups. */
    val httpClient: OkHttpClient = RadioApi.defaultClient()

    val api: RadioApi = RadioApi({ settings }, httpClient)

    val nowPlayingRepository: NowPlayingRepository = NowPlayingRepository(api)

    val artworkRepository: ArtworkRepository = ArtworkRepository(httpClient)

    /** EMS/ATC scanner backend (separate Pi service). Shares the HTTP client. */
    val scannerApi: ScannerApi = ScannerApi({ settings }, httpClient)

    val scannerRepository: ScannerRepository = ScannerRepository(scannerApi)

    /**
     * The active ExoPlayer audio session id, published by PlaybackService and
     * consumed by the reactive visualizer to attach a [android.media.audiofx.Visualizer].
     * 0 means "not yet known".
     */
    val audioSessionId: MutableStateFlow<Int> = MutableStateFlow(0)
    val audioSession: StateFlow<Int> get() = audioSessionId

    /**
     * "Duck on talk" option: near-mute the stream during talk/commercial
     * stretches, prompt volume restore when music returns. Persisted; consumed
     * by DuckController in PlaybackService. [duckStatus] is the live state +
     * classifier score it publishes back for the UI readout.
     */
    val duckEnabled: MutableStateFlow<Boolean> =
        MutableStateFlow(prefs.getBoolean(PREF_DUCK_ENABLED, false))
    val duckStatus: MutableStateFlow<DuckStatus> = MutableStateFlow(DuckStatus())

    fun setDuckEnabled(on: Boolean) {
        duckEnabled.value = on
        prefs.edit().putBoolean(PREF_DUCK_ENABLED, on).apply()
    }

    private companion object {
        const val PREF_DUCK_ENABLED = "duck_talk_enabled"
    }
}
