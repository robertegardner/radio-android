plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/** Short git SHA at build time (matches :app) so a watch build can be identified. */
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
    namespace = "io.rg2.radio.wear"
    compileSdk = 36

    defaultConfig {
        // Same applicationId as the phone app: this is its Wear OS counterpart.
        applicationId = "io.rg2.radio"
        minSdk = 30          // Wear OS 3+
        targetSdk = 34       // current Wear OS platform
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
    // Shared radio + scanner data/networking layer.
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Jetpack Compose (shared BOM) + Compose for Wear OS (its own Material).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Media3: the watch runs its own ExoPlayer + media session (standalone).
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
}
