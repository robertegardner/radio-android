package io.rg2.radio.auth

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HostTokenCacheTest {
    private val server = MockWebServer()
    private var nowMs = 1_000_000L
    private lateinit var repo: AuthRepository
    private lateinit var cache: HostTokenCache

    private fun idToken(username: String = "kid", groups: List<String> = listOf("family")) = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"preferred_username":"$username","groups":${groups.joinToString("\",\"", "[\"", "\"]")}}""".toByteArray()) + ".s"

    @Before fun setUp() {
        server.start()
        val cfg = AuthConfig.DEFAULT.copy(baseUrl = server.url("").toString().trimEnd('/'))
        val oauth = OAuthClient(cfg, OkHttpClient())
        repo = AuthRepository(cfg, InMemoryTokenStore(), oauth) { nowMs }
        cache = HostTokenCache(cfg, repo, oauth) { nowMs }
    }

    @After fun tearDown() = server.shutdown()

    private fun signIn() = repo.completeLogin(TokenResponse("login", "rt", idToken(), 3600))

    @Test fun unmappedHostIsNull() {
        signIn()
        assertNull(cache.tokenFor("example.com"))
        assertEquals(0, server.requestCount)
    }

    @Test fun signedOutIsNull() {
        assertNull(cache.tokenFor("radio.rg2.io"))
        assertEquals(0, server.requestCount)
    }

    @Test fun exchangesOnceThenServesFromCache() {
        signIn()
        server.enqueue(MockResponse().setBody("""{"access_token":"hostTok","expires_in":86400}"""))
        assertEquals("hostTok", cache.tokenFor("radio.rg2.io"))
        assertEquals("hostTok", cache.tokenFor("radio.rg2.io"))
        assertEquals(1, server.requestCount)
        val body = server.takeRequest().body.readUtf8()
        assertEquals(true, body.contains("client_id=EejQH8q8sJLXZIdRiCkeq9L5Ytn6xTzB0x84f9jU"))
    }

    @Test fun reExchangesWhenNearExpiry() {
        signIn()
        server.enqueue(MockResponse().setBody("""{"access_token":"t1","expires_in":600}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"t2","expires_in":600}"""))
        assertEquals("t1", cache.tokenFor("ems.rg2.io"))
        nowMs += 301_000 // < 5 min left
        assertEquals("t2", cache.tokenFor("ems.rg2.io"))
    }

    @Test fun invalidGrantRefreshesLoginThenRetries() {
        signIn()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"login2","refresh_token":"rt2","expires_in":3600}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"hostTok","expires_in":86400}"""))
        assertEquals("hostTok", cache.tokenFor("icecast.rg2.io"))
        assertEquals(3, server.requestCount)
    }

    @Test fun twiceRefusedMarksNoAccessAndStopsCalling() {
        signIn()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"login2","refresh_token":"rt2","expires_in":3600}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        assertNull(cache.tokenFor("ems.rg2.io"))
        assertNull(cache.tokenFor("ems.rg2.io"))
        assertEquals(3, server.requestCount)
    }

    @Test fun invalidateForcesNewExchange() {
        signIn()
        server.enqueue(MockResponse().setBody("""{"access_token":"t1","expires_in":86400}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"t2","expires_in":86400}"""))
        assertEquals("t1", cache.tokenFor("radio.rg2.io"))
        cache.invalidate("radio.rg2.io")
        assertEquals("t2", cache.tokenFor("radio.rg2.io"))
    }

    @Test fun concurrentCallersExchangeOnce() {
        signIn()
        server.enqueue(MockResponse().setBody("""{"access_token":"hostTok","expires_in":86400}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        val results = java.util.Collections.synchronizedList(mutableListOf<String?>())
        repeat(4) { pool.execute { results += cache.tokenFor("radio.rg2.io"); done.countDown() } }
        done.await(5, TimeUnit.SECONDS)
        assertEquals(List(4) { "hostTok" }, results.toList())
        assertEquals(1, server.requestCount)
    }

    @Test fun noAccessResetsForADifferentUser() {
        repo.completeLogin(TokenResponse("login", "rt", idToken("kid", listOf("family")), 3600))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"login2","refresh_token":"rt2","expires_in":3600}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        assertNull(cache.tokenFor("ems.rg2.io"))
        repo.completeLogin(TokenResponse("login3", "rt3", idToken("admin", listOf("homelab-admin")), 3600))
        server.enqueue(MockResponse().setBody("""{"access_token":"hostTok","expires_in":86400}"""))
        assertEquals("hostTok", cache.tokenFor("ems.rg2.io"))
    }
}
