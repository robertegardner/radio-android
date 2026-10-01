package io.rg2.radio.auth

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface DeviceLoginState {
    data object Idle : DeviceLoginState
    data class ShowCode(val userCode: String, val verificationUri: String) : DeviceLoginState
    data object Success : DeviceLoginState
    data class Failed(val message: String) : DeviceLoginState
}

/**
 * RFC 8628 device-code sign-in for the watch: show a short code, the user
 * approves at authentik.bobgardner.org/device on any browser, we poll. The watch
 * keeps its own refresh token (rotation makes sharing the phone's unsafe).
 * Blocking [OAuthClient] calls — run on Dispatchers.IO in production.
 */
class DeviceCodeLogin(private val oauth: OAuthClient, private val repo: AuthRepository) {
    private val _state = MutableStateFlow<DeviceLoginState>(DeviceLoginState.Idle)
    val state: StateFlow<DeviceLoginState> = _state

    suspend fun run() {
        val auth = when (val r = oauth.deviceAuthorize()) {
            is OAuthResult.Ok -> r.value
            else -> { _state.value = DeviceLoginState.Failed("Couldn't reach sign-in server"); return }
        }
        _state.value = DeviceLoginState.ShowCode(auth.userCode, auth.verificationUri)
        var intervalMs = auth.interval * 1000
        while (true) {
            delay(intervalMs)
            when (val r = oauth.pollDevice(auth.deviceCode)) {
                is OAuthResult.Ok -> {
                    _state.value = if (repo.completeLogin(r.value)) DeviceLoginState.Success
                    else DeviceLoginState.Failed("Sign-in response was incomplete")
                    return
                }
                is OAuthResult.Err -> when (r.error) {
                    "authorization_pending" -> Unit
                    "slow_down" -> intervalMs += 5_000
                    "expired_token" -> { _state.value = DeviceLoginState.Failed("Code expired — try again"); return }
                    "access_denied" -> { _state.value = DeviceLoginState.Failed("Not allowed"); return }
                    else -> { _state.value = DeviceLoginState.Failed("Sign-in failed (${r.error})"); return }
                }
                is OAuthResult.Network -> Unit // transient; keep polling until the code expires server-side
            }
        }
    }
}
