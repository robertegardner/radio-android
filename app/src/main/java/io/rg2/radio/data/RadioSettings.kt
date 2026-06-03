package io.rg2.radio.data

import okhttp3.Credentials

/**
 * Source of the user-configurable connection settings: the backend base URL and
 * (optional) write credentials for `/api/tune`.
 *
 * CLAUDE.md requires base URL + credentials to be user-configurable (settings
 * screen) and stored in EncryptedSharedPreferences/DataStore — never in the
 * repo, never hardcoded. This interface keeps [RadioApi] decoupled from *where*
 * the values live; the encrypted-store implementation lands with the settings
 * screen. Until then, [InMemoryRadioSettings] provides sane defaults.
 */
interface RadioSettings {
    /** e.g. `https://radio.rg2.io`. No trailing slash required. */
    val baseUrl: String

    /**
     * Value for the `Authorization` header on write requests, or null if no
     * credentials are configured (auth isn't live on the backend yet). Build
     * with [basicAuthHeader] when the user supplies a username/password.
     */
    val authHeader: String?

    companion object {
        const val DEFAULT_BASE_URL = "https://radio.rg2.io"
        const val DEFAULT_STREAM_URL = "https://icecast.rg2.io/fm.mp3"

        /** HTTP Basic header value for NPMplus basic auth. */
        fun basicAuthHeader(username: String, password: String): String =
            Credentials.basic(username, password)
    }
}

/** Default in-memory settings. Replaced by an encrypted-store impl later. */
data class InMemoryRadioSettings(
    override val baseUrl: String = RadioSettings.DEFAULT_BASE_URL,
    override val authHeader: String? = null,
) : RadioSettings
