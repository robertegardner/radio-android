package io.rg2.radio

import android.app.Application

/**
 * Application entry point. A placeholder for now; will own app-wide singletons
 * (the [io.rg2.radio.data.RadioApi] client, settings/credentials store, and the
 * Media3 player wiring) as those land.
 */
class RadioApp : Application()
