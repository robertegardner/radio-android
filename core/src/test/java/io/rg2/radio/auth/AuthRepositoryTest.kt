package io.rg2.radio.auth

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class AuthRepositoryTest {
    private val server = MockWebServer()
    private val store = InMemoryTokenStore()
    private var nowMs = 1_000_000L
    private lateinit var repo: AuthRepository

    private fun idToken(user: String, vararg groups: String): String {
        val p = """{"preferred_username":"$user","groups":[${groups.joinToString(",") { "\"$it\"" }}]}"""
        return "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(p.toByteArray()) + ".s"
    }

    @Before fun setUp() {
        server.start()
        val cfg = AuthConfig.DEFAULT.copy(baseUrl = server.url("").toString().trimEnd('/'))
        repo = AuthRepository(cfg, store, OAuthClient(cfg, OkHttpClient())) { nowMs }
    }

    @After fun tearDown() = server.shutdown()

    @Test fun startsSignedOutWithEmptyStore() {
        assertEquals(AuthStatus.SignedOut, repo.status.value)
        assertNull(repo.freshAccessToken())
    }

    @Test fun completeLoginStoresIdentityAndRole() {
        assertTrue(repo.completeLogin(TokenResponse("at", "rt", idToken("rg", "family", "homelab-admin"), 3600)))
        assertEquals(AuthStatus.SignedIn("rg", listOf("family", "homelab-admin"), isAdmin = true), repo.status.value)
        assertEquals("rt", store.state!!.refreshToken)
        assertEquals("at", repo.freshAccessToken())
    }

    @Test fun refreshPersistsRotatedRefreshToken() {
        repo.completeLogin(TokenResponse("at", "rt1", idToken("kid", "family"), 3600))
        nowMs += 3_600_000
        server.enqueue(MockResponse().setBody("""{"access_token":"at2","refresh_token":"rt2","expires_in":3600}"""))
        assertEquals("at2", repo.freshAccessToken())
        assertEquals("rt2", store.state!!.refreshToken)
    }

    @Test fun refreshInvalidGrantExpiresSession() {
        repo.completeLogin(TokenResponse("at", "rt", idToken("kid", "family"), 3600))
        nowMs += 3_600_000
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        assertNull(repo.freshAccessToken())
        assertEquals(AuthStatus.Expired, repo.status.value)
    }

    @Test fun refreshNetworkErrorKeepsSession() {
        repo.completeLogin(TokenResponse("at", "rt", idToken("kid", "family"), 3600))
        nowMs += 3_600_000
        server.shutdown()
        assertNull(repo.freshAccessToken())
        assertTrue(repo.status.value is AuthStatus.SignedIn)
    }

    @Test fun signOutRevokesAndClears() {
        repo.completeLogin(TokenResponse("at", "rt", idToken("kid", "family"), 3600))
        server.enqueue(MockResponse().setResponseCode(200))
        repo.signOut()
        assertEquals("/application/o/revoke/", server.takeRequest().path)
        assertNull(store.state)
        assertEquals(AuthStatus.SignedOut, repo.status.value)
    }

    @Test fun restoresSessionFromStore() {
        val s = AuthState("rt", "at", nowMs + 60_000, "kid", listOf("family"))
        val cfg = AuthConfig.DEFAULT.copy(baseUrl = server.url("").toString().trimEnd('/'))
        val r = AuthRepository(cfg, InMemoryTokenStore(s), OAuthClient(cfg, OkHttpClient())) { nowMs }
        assertEquals(AuthStatus.SignedIn("kid", listOf("family"), isAdmin = false), r.status.value)
    }

    @Test fun hostTokensPersistAndDrop() {
        repo.completeLogin(TokenResponse("at", "rt", idToken("kid", "family"), 3600))
        repo.putHostToken("radio.rg2.io", HostToken("h", nowMs + 1000))
        assertEquals("h", store.state!!.hostTokens["radio.rg2.io"]!!.token)
        repo.dropHostToken("radio.rg2.io")
        assertNull(repo.hostToken("radio.rg2.io"))
    }
}
