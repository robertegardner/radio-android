package io.rg2.radio.auth

/** What [AuthInterceptor] needs — kept small so it can be faked. */
interface HostTokenSource {
    fun tokenFor(host: String): String?
    fun invalidate(host: String)
    fun isSignedIn(): Boolean
}

/**
 * Per-host tokens. The outpost only accepts JWTs issued by that host's own proxy
 * provider, so the login token is exchanged (client_assertion jwt-bearer) per
 * host and cached until < 5 min of life remain. Synchronized: the ~1 s polls and
 * a stream start can race on first use, and must exchange once.
 */
class HostTokenCache(
    private val config: AuthConfig,
    private val repo: AuthRepository,
    private val oauth: OAuthClient,
    private val now: () -> Long = System::currentTimeMillis,
) : HostTokenSource {
    private val noAccess = mutableMapOf<String, String>()

    override fun isSignedIn() = repo.status.value is AuthStatus.SignedIn

    @Synchronized
    override fun tokenFor(host: String): String? {
        val clientId = config.hostClientIds[host] ?: return null
        if (!isSignedIn()) return null
        val currentUsername = (repo.status.value as? AuthStatus.SignedIn)?.username ?: return null
        if (noAccess[host] == currentUsername) return null
        repo.hostToken(host)?.let { if (it.expiresAtMs - now() > MIN_LIFE_MS) return it.token }
        val login = repo.freshAccessToken() ?: return null
        val first = oauth.exchange(login, clientId)
        val result = if (first is OAuthResult.Err && first.error == "invalid_grant") {
            val again = repo.forceRefresh() ?: return null
            oauth.exchange(again, clientId)
        } else first
        return when (result) {
            is OAuthResult.Ok -> {
                repo.putHostToken(host, HostToken(result.value.accessToken, now() + result.value.expiresIn * 1000))
                result.value.accessToken
            }
            is OAuthResult.Err -> { if (result.error == "invalid_grant") noAccess[host] = currentUsername; null }
            is OAuthResult.Network -> null
        }
    }

    @Synchronized
    override fun invalidate(host: String) = repo.dropHostToken(host)

    private companion object { const val MIN_LIFE_MS = 5 * 60_000L }
}
