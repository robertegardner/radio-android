package io.rg2.radio.auth

import kotlinx.serialization.Serializable

@Serializable
data class HostToken(val token: String, val expiresAtMs: Long)

/** The persisted sign-in session (encrypted at rest by the platform [TokenStore]). */
@Serializable
data class AuthState(
    val refreshToken: String,
    val accessToken: String,
    val accessExpiresAtMs: Long,
    val username: String,
    val groups: List<String>,
    val hostTokens: Map<String, HostToken> = emptyMap(),
)

interface TokenStore {
    fun load(): AuthState?
    fun save(state: AuthState?)
}

class InMemoryTokenStore(var state: AuthState? = null) : TokenStore {
    override fun load() = state
    override fun save(state: AuthState?) { this.state = state }
}
