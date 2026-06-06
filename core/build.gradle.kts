plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Shared data/networking layer for both the phone (`:app`) and the watch
 * (`:wear`). Holds the backend DTOs, the radio + scanner HTTP clients, settings,
 * and the polling repositories — the parts that must stay in lockstep with the
 * live backends, so they live in exactly one place.
 *
 * Networking deps are exposed as `api` so consumers inherit OkHttp /
 * serialization / coroutines without re-declaring them.
 */
android {
    namespace = "io.rg2.radio.core"
    compileSdk = 36

    defaultConfig {
        // Lower than either consumer (app 26, wear 30) so both can depend on it.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.android)
}
