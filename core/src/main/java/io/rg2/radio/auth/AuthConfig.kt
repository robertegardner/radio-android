package io.rg2.radio.auth

/**
 * Authentik sign-in constants (spec 2026-10-01-authentik-signin). The host map is
 * the platform registry's per-host proxy-provider client_ids — the app exchanges
 * its login token for a token issued by each host's own provider (the outpost
 * only accepts those). Changes only if a provider is recreated.
 */
data class AuthConfig(
    val baseUrl: String,
    val clientId: String,
    val redirectUri: String,
    val loginScopes: String,
    val exchangeScopes: String,
    val hostClientIds: Map<String, String>,
    val adminGroup: String,
) {
    val tokenUrl get() = "$baseUrl/application/o/token/"
    val authorizeUrl get() = "$baseUrl/application/o/authorize/"
    val deviceUrl get() = "$baseUrl/application/o/device/"
    val revokeUrl get() = "$baseUrl/application/o/revoke/"

    companion object {
        val DEFAULT = AuthConfig(
            baseUrl = "https://authentik.bobgardner.org",
            clientId = "hemTnLT7eGgimzjqizkXFTGlWI0phGmjPaLaWm0O",
            redirectUri = "io.rg2.radio:/oauth2redirect",
            loginScopes = "openid profile email offline_access",
            // profile/email/ak_proxy are what make the outpost forward
            // X-authentik-username/groups to the backend (spike 2026-10-01).
            exchangeScopes = "openid profile email ak_proxy",
            hostClientIds = mapOf(
                "radio.rg2.io" to "EejQH8q8sJLXZIdRiCkeq9L5Ytn6xTzB0x84f9jU",
                "ems.rg2.io" to "qMx0qI2CruW3bSQK8aC4T2ytq2xSmX0jaP5wyW5p",
                "icecast.rg2.io" to "0udNEfQq1J7cKJO5JequfHA4YEPDWGe6wZIRFXlK",
            ),
            adminGroup = "homelab-admin",
        )
    }
}
