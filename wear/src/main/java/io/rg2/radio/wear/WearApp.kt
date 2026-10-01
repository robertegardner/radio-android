package io.rg2.radio.wear

import android.app.Application
import io.rg2.radio.data.InMemoryRadioSettings
import io.rg2.radio.data.NowPlayingRepository
import io.rg2.radio.data.RadioApi
import io.rg2.radio.data.RadioSettings
import io.rg2.radio.data.ScannerApi
import io.rg2.radio.data.ScannerRepository
import okhttp3.OkHttpClient

/**
 * Wear entry point. Mirrors the phone app's hand-rolled container (house style:
 * no DI framework), but lean — the watch only needs the shared HTTP clients and
 * polling repositories from `:core`. Standalone: it talks to the backends over
 * the watch's own network, no phone bridge.
 */
class WearApp : Application() {
    lateinit var container: WearContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = WearContainer(io.rg2.radio.auth.KeystoreTokenStore(this))
    }
}

class WearContainer(tokenStore: io.rg2.radio.auth.TokenStore) {
    @Volatile
    var settings: RadioSettings = InMemoryRadioSettings()

    val authConfig = io.rg2.radio.auth.AuthConfig.DEFAULT
    val oauth = io.rg2.radio.auth.OAuthClient(authConfig, RadioApi.defaultClient())
    val auth = io.rg2.radio.auth.AuthRepository(authConfig, tokenStore, oauth)
    val hostTokens = io.rg2.radio.auth.HostTokenCache(authConfig, auth, oauth)
    val httpClient: OkHttpClient = RadioApi.defaultClient(io.rg2.radio.auth.AuthInterceptor(hostTokens))

    val api: RadioApi = RadioApi({ settings }, httpClient)
    val scannerApi: ScannerApi = ScannerApi({ settings }, httpClient)

    val nowPlayingRepository: NowPlayingRepository = NowPlayingRepository(api)
    val scannerRepository: ScannerRepository = ScannerRepository(scannerApi)
}
