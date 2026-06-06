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
        container = WearContainer()
    }
}

class WearContainer {
    @Volatile
    var settings: RadioSettings = InMemoryRadioSettings()

    val httpClient: OkHttpClient = RadioApi.defaultClient()

    val api: RadioApi = RadioApi({ settings }, httpClient)
    val scannerApi: ScannerApi = ScannerApi({ settings }, httpClient)

    val nowPlayingRepository: NowPlayingRepository = NowPlayingRepository(api)
    val scannerRepository: ScannerRepository = ScannerRepository(scannerApi)
}
