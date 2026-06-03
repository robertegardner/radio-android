package io.rg2.radio

import android.app.Application
import io.rg2.radio.data.InMemoryRadioSettings
import io.rg2.radio.data.NowPlayingRepository
import io.rg2.radio.data.RadioApi
import io.rg2.radio.data.RadioSettings

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

    val api: RadioApi = RadioApi({ settings })

    val nowPlayingRepository: NowPlayingRepository = NowPlayingRepository(api)
}
