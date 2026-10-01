package io.rg2.radio.auth

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class SignInRequiredException : IOException("Sign in to use the radio away from home")

/**
 * Adds `Authorization: Bearer <host token>` for mapped hosts and recovers once
 * from an expired/rejected token. OkHttp FOLLOWS the outpost's 302, so an
 * application interceptor only sees the final login-page 200 — detection walks
 * [Response.priorResponse] for a redirect into `/outpost.goauthentik.io/`.
 * On LAN the NPM bypass answers without a redirect, so signed-out LAN use just works.
 */
class AuthInterceptor(
    private val source: HostTokenSource,
    private val hostOf: (HttpUrl) -> String = { it.host },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val host = hostOf(req.url)
        val token = source.tokenFor(host)
        val first = chain.proceed(req.withBearer(token))
        if (!needsSignIn(first)) return first
        first.close()
        if (token == null) throw SignInRequiredException()
        source.invalidate(host)
        val retry = chain.proceed(req.withBearer(source.tokenFor(host) ?: throw SignInRequiredException()))
        if (needsSignIn(retry)) { retry.close(); throw SignInRequiredException() }
        return retry
    }

    private fun Request.withBearer(token: String?) =
        if (token == null) this else newBuilder().header("Authorization", "Bearer $token").build()

    private fun needsSignIn(resp: Response): Boolean {
        if (resp.code == 401) return true
        var r: Response? = resp.priorResponse
        while (r != null) {
            if (r.header("Location")?.contains("/outpost.goauthentik.io/") == true) return true
            r = r.priorResponse
        }
        return false
    }
}
