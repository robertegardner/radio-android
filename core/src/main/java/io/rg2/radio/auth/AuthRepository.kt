package io.rg2.radio.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface AuthStatus {
    data object SignedOut : AuthStatus
    data class SignedIn(val username: String, val groups: List<String>, val isAdmin: Boolean) : AuthStatus
    /** Refresh token rejected (revoked / >30 d) — user must sign in again. */
    data object Expired : AuthStatus
}

/**
 * The sign-in session. Blocking + synchronized: callers are OkHttp threads (via
 * [HostTokenCache]) or Dispatchers.IO. Refresh tokens ROTATE on every use, so
 * the new one is persisted before anything else (spike 2026-10-01).
 */
class AuthRepository(
    private val config: AuthConfig,
    private val store: TokenStore,
    private val oauth: OAuthClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var state: AuthState? = store.load()
    private val _status = MutableStateFlow(statusOf(state))
    val status: StateFlow<AuthStatus> = _status

    private fun statusOf(s: AuthState?): AuthStatus =
        if (s == null) AuthStatus.SignedOut
        else AuthStatus.SignedIn(s.username, s.groups, config.adminGroup in s.groups)

    @Synchronized
    private fun persist(s: AuthState?, expired: Boolean = false) {
        state = s
        store.save(s)
        _status.value = if (expired) AuthStatus.Expired else statusOf(s)
    }

    fun loginWithCode(code: String, verifier: String): Boolean =
        when (val r = oauth.redeemCode(code, verifier)) {
            is OAuthResult.Ok -> completeLogin(r.value)
            else -> false
        }

    @Synchronized
    fun completeLogin(tokens: TokenResponse): Boolean {
        val refresh = tokens.refreshToken ?: return false
        val claims = tokens.idToken?.let(JwtClaims::parse) ?: JwtClaims.parse(tokens.accessToken) ?: return false
        persist(AuthState(refresh, tokens.accessToken, now() + tokens.expiresIn * 1000,
            claims.username, claims.groups))
        return true
    }

    /** Valid login access token (refreshing if within 60 s of expiry), or null. */
    @Synchronized
    fun freshAccessToken(): String? {
        val s = state ?: return null
        if (s.accessExpiresAtMs - now() > 60_000) return s.accessToken
        return forceRefresh()
    }

    @Synchronized
    fun forceRefresh(): String? {
        val s = state ?: return null
        return when (val r = oauth.refresh(s.refreshToken)) {
            is OAuthResult.Ok -> {
                persist(s.copy(
                    refreshToken = r.value.refreshToken ?: s.refreshToken,
                    accessToken = r.value.accessToken,
                    accessExpiresAtMs = now() + r.value.expiresIn * 1000,
                ))
                r.value.accessToken
            }
            is OAuthResult.Err -> {
                if (r.error == "invalid_grant") persist(null, expired = true)
                null
            }
            is OAuthResult.Network -> null
        }
    }

    @Synchronized fun hostToken(host: String): HostToken? = state?.hostTokens?.get(host)

    @Synchronized
    fun putHostToken(host: String, t: HostToken) {
        val s = state ?: return
        persist(s.copy(hostTokens = s.hostTokens + (host to t)))
    }

    @Synchronized
    fun dropHostToken(host: String) {
        val s = state ?: return
        if (host in s.hostTokens) persist(s.copy(hostTokens = s.hostTokens - host))
    }

    fun signOut() {
        val s = synchronized(this) { state.also { persist(null) } }
        s?.let { oauth.revoke(it.refreshToken) }
    }
}
