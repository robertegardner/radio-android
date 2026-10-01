package io.rg2.radio.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class DeviceCodeLoginTest {
    private val server = MockWebServer()
    private lateinit var login: DeviceCodeLogin
    private lateinit var repo: AuthRepository

    private val authorize = MockResponse().setBody(
        """{"device_code":"dc","user_code":"WXYZ-0001","verification_uri":"https://authentik.bobgardner.org/device","expires_in":600,"interval":5}""")
    private fun pending(err: String) = MockResponse().setResponseCode(400).setBody("""{"error":"$err"}""")
    private val idTok = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"preferred_username":"kid","groups":["family"]}""".toByteArray()) + ".s"
    private val tokens = MockResponse().setBody("""{"access_token":"at","refresh_token":"rt","id_token":"$idTok","expires_in":3600}""")

    @Before fun setUp() {
        server.start()
        val cfg = AuthConfig.DEFAULT.copy(baseUrl = server.url("").toString().trimEnd('/'))
        val oauth = OAuthClient(cfg, OkHttpClient())
        repo = AuthRepository(cfg, InMemoryTokenStore(), oauth)
        login = DeviceCodeLogin(oauth, repo)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun pendingThenSuccessSignsIn() = runTest {
        server.enqueue(authorize); server.enqueue(pending("authorization_pending")); server.enqueue(tokens)
        login.run()
        assertEquals(DeviceLoginState.Success, login.state.value)
        assertTrue(repo.status.value is AuthStatus.SignedIn)
    }

    @Test @OptIn(ExperimentalCoroutinesApi::class) fun slowDownStillCompletes() = runTest {
        server.enqueue(authorize); server.enqueue(pending("slow_down")); server.enqueue(tokens)
        login.run()
        assertEquals(DeviceLoginState.Success, login.state.value)
        assertEquals(15_000L, testScheduler.currentTime)
    }

    @Test fun expiredCodeFails() = runTest {
        server.enqueue(authorize); server.enqueue(pending("expired_token"))
        login.run()
        assertEquals(DeviceLoginState.Failed("Code expired — try again"), login.state.value)
    }

    @Test fun deniedFails() = runTest {
        server.enqueue(authorize); server.enqueue(pending("access_denied"))
        login.run()
        assertEquals(DeviceLoginState.Failed("Not allowed"), login.state.value)
    }

    @Test fun persistentNetworkFailureStopsAtExpiry() = runTest {
        withTimeout(30_000) {
            val shortExpire = MockResponse().setBody(
                """{"device_code":"dc","user_code":"WXYZ-0001","verification_uri":"https://authentik.bobgardner.org/device","expires_in":12,"interval":5}""")
            server.enqueue(shortExpire)
            repeat(5) { server.enqueue(pending("authorization_pending")) }
            login.run()
            assertEquals(DeviceLoginState.Failed("Code expired — try again"), login.state.value)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun rerunAfterFailureStartsFresh() = runTest {
        server.enqueue(authorize); server.enqueue(pending("access_denied"))
        login.run()
        assertEquals(DeviceLoginState.Failed("Not allowed"), login.state.value)

        server.enqueue(authorize); server.enqueue(tokens)
        login.run()
        assertEquals(DeviceLoginState.Success, login.state.value)
        assertTrue(repo.status.value is AuthStatus.SignedIn)
    }
}
