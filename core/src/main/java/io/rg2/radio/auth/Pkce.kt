package io.rg2.radio.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 7636 PKCE helpers for the browser sign-in. */
object Pkce {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun newVerifier(): String = b64.encodeToString(ByteArray(32).also(random::nextBytes))

    fun newState(): String = b64.encodeToString(ByteArray(16).also(random::nextBytes))

    fun challenge(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizeUrl(config: AuthConfig, challenge: String, state: String): String =
        config.authorizeUrl.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", config.clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", config.redirectUri)
            .addQueryParameter("scope", config.loginScopes)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .build().toString()
}

/** Display-only identity from an ID token (the server stays the authority). */
data class JwtClaims(val username: String, val groups: List<String>) {
    companion object {
        fun parse(jwt: String): JwtClaims? = runCatching {
            val payload = jwt.split(".")[1]
            val obj = Json.parseToJsonElement(
                String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8),
            ).jsonObject
            JwtClaims(
                username = obj["preferred_username"]!!.jsonPrimitive.content,
                groups = obj["groups"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            )
        }.getOrNull()
    }
}
