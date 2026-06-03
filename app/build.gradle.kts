plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Short git SHA of the working tree at build time, with a -dirty suffix for
 *  uncommitted changes — surfaced in the UI so test devices can be matched to a
 *  build. Uses providers.exec so it stays configuration-cache compatible. */
fun gitRevision(): String {
    fun exec(vararg cmd: String): String =
        providers.exec { commandLine(*cmd) }.standardOutput.asText.get().trim()
    return try {
        val sha = exec("git", "rev-parse", "--short", "HEAD")
        if (sha.isEmpty()) return "nogit"
        val dirty = if (exec("git", "status", "--porcelain").isNotEmpty()) "-dirty" else ""
        "$sha$dirty"
    } catch (_: Exception) {
        "nogit"
    }
}

android {
    namespace = "io.rg2.radio"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.rg2.radio"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GIT_SHA", "\"${gitRevision()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Networking — OkHttp + kotlinx.serialization (house style: minimal deps,
    // no Retrofit). See docs/api.md for the schemas these model.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Media3: ExoPlayer for the Icecast MP3 stream + MediaLibraryService for
    // the media session (lock-screen/notification transport) and the Android
    // Auto browse tree.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    // Album-art image loading (remote cover art from the iTunes Search API).
    implementation(libs.coil.compose)
}
