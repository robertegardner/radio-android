package io.rg2.radio.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

@Serializable
data class DeviceAuthorization(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 600,
    val interval: Long = 5,
)

sealed interface OAuthResult<out T> {
    data class Ok<T>(val value: T) : OAuthResult<T>
    /** OAuth `error` code, or `http_<status>` when the body isn't an OAuth error. */
    data class Err(val error: String) : OAuthResult<Nothing>
    data class Network(val cause: IOException) : OAuthResult<Nothing>
}

/**
 * Blocking calls to Authentik's OAuth endpoints. Uses its own [http] client
 * WITHOUT [AuthInterceptor] (the interceptor calls into this; sharing would recurse).
 */
class OAuthClient(private val config: AuthConfig, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    fun redeemCode(code: String, verifier: String) = token(
        "grant_type" to "authorization_code", "code" to code, "code_verifier" to verifier,
        "redirect_uri" to config.redirectUri, "client_id" to config.clientId,
    )

    fun refresh(refreshToken: String) = token(
        "grant_type" to "refresh_token", "refresh_token" to refreshToken,
        "client_id" to config.clientId,
    )

    fun exchange(loginAccessToken: String, hostClientId: String) = token(
        "grant_type" to "client_credentials",
        "client_assertion_type" to "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
        "client_assertion" to loginAccessToken,
        "client_id" to hostClientId,
        "scope" to config.exchangeScopes,
    )

    fun pollDevice(deviceCode: String) = token(
        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        "device_code" to deviceCode, "client_id" to config.clientId,
    )

    fun deviceAuthorize(): OAuthResult<DeviceAuthorization> =
        post(config.deviceUrl, listOf("client_id" to config.clientId, "scope" to config.loginScopes)) {
            json.decodeFromString(DeviceAuthorization.serializer(), it)
        }

    /** Best effort — a failed revoke must never block local sign-out. */
    fun revoke(refreshToken: String) {
        runCatching {
            post(config.revokeUrl, listOf("token" to refreshToken, "client_id" to config.clientId)) { it }
        }
    }

    private fun token(vararg fields: Pair<String, String>): OAuthResult<TokenResponse> =
        post(config.tokenUrl, fields.toList()) { json.decodeFromString(TokenResponse.serializer(), it) }

    private fun <T> post(url: String, fields: List<Pair<String, String>>, parse: (String) -> T): OAuthResult<T> {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return try {
            http.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    OAuthResult.Ok(parse(text))
                } else {
                    val err = runCatching {
                        json.parseToJsonElement(text).jsonObject["error"]!!.jsonPrimitive.content
                    }.getOrNull()
                    OAuthResult.Err(err ?: "http_${resp.code}")
                }
            }
        } catch (e: IOException) {
            OAuthResult.Network(e)
        }
    }
}
