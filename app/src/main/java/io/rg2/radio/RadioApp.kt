package io.rg2.radio

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import io.rg2.radio.audio.AudioTapHub
import io.rg2.radio.auth.KeystoreTokenStore
import io.rg2.radio.auth.TokenStore
import io.rg2.radio.audio.DuckStatus
import io.rg2.radio.audio.StereoLevels
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
        container = AppContainer(getSharedPreferences("radio", Context.MODE_PRIVATE), KeystoreTokenStore(this))
    }
}

/**
 * Shared singletons. [settings] is currently in-memory (default base URL, no
 * auth); it gets swapped for the encrypted store when the settings screen
 * lands. Everything reads settings through the `{ settings }` lambda so that
 * swap won't require rebuilding [api].
 */
class AppContainer(private val prefs: SharedPreferences, tokenStore: TokenStore) {
    @Volatile
    var settings: RadioSettings = InMemoryRadioSettings()

    val authConfig = io.rg2.radio.auth.AuthConfig.DEFAULT

    /** Plain client for Authentik itself (must NOT carry AuthInterceptor). */
    private val oauthHttp: OkHttpClient = RadioApi.defaultClient()
    val oauth = io.rg2.radio.auth.OAuthClient(authConfig, oauthHttp)
    val auth = io.rg2.radio.auth.AuthRepository(authConfig, tokenStore, oauth)
    val hostTokens = io.rg2.radio.auth.HostTokenCache(authConfig, auth, oauth)

    /** One OkHttp client (shared pool) for backends, artwork AND ExoPlayer streams. */
    val httpClient: OkHttpClient = RadioApi.defaultClient(io.rg2.radio.auth.AuthInterceptor(hostTokens))

    /** Non-null = show "sign in to listen away from home" (set by PlaybackService). */
    val authHint: MutableStateFlow<String?> = MutableStateFlow(null)

    /** One-shot user-facing message for failed actions (admin required / sign-in required). */
    val actionMessage: MutableStateFlow<String?> = MutableStateFlow(null)

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
     * The single shared Visualizer tap on that session — viz styles and the
     * duck classifier all consume through here (a second Visualizer instance
     * on one session fails on real devices; never construct one directly).
     */
    val audioTap: AudioTapHub = AudioTapHub()

    /**
     * Per-channel L/R levels from the player's own PCM tap (see
     * [io.rg2.radio.audio.StereoLevelTap]) — published by PlaybackService,
     * consumed by the tuner panel's L/R meters. Independent of the Visualizer
     * hub and needs no RECORD_AUDIO.
     */
    val stereoLevels: MutableStateFlow<StereoLevels> = MutableStateFlow(StereoLevels())

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
