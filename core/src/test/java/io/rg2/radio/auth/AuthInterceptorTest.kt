package io.rg2.radio.auth

import io.rg2.radio.data.ADMIN_MESSAGE
import io.rg2.radio.data.AdminRequiredException
import io.rg2.radio.data.Band
import io.rg2.radio.data.InMemoryRadioSettings
import io.rg2.radio.data.RadioApi
import io.rg2.radio.data.TuneRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {
    private val server = MockWebServer()

    private class FakeSource(var signedIn: Boolean = true) : HostTokenSource {
        val tokens = ArrayDeque(listOf("t1", "t2", "t3"))
        var invalidated = 0
        override fun tokenFor(host: String) = if (signedIn && host == "radio.rg2.io") tokens.firstOrNull() else null
        override fun invalidate(host: String) { invalidated++; tokens.removeFirstOrNull() }
        override fun isSignedIn() = signedIn
    }

    private lateinit var source: FakeSource
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        server.start()
        source = FakeSource()
        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(source) { url -> if (url.port == server.port) "radio.rg2.io" else url.host })
            .build()
    }

    @After fun tearDown() = server.shutdown()

    private fun get(path: String = "/api/status") =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    private fun loginRedirect() = MockResponse().setResponseCode(302)
        .setHeader("Location", "/outpost.goauthentik.io/start?rd=x")

    @Test fun attachesBearerForMappedHost() {
        server.enqueue(MockResponse().setBody("{}"))
        get().close()
        assertEquals("Bearer t1", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun noHeaderWhenSignedOut() {
        source.signedIn = false
        server.enqueue(MockResponse().setBody("{}"))
        get().close()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun retriesOnceWhenRedirectedToOutpost() {
        server.enqueue(loginRedirect())
        server.enqueue(MockResponse().setBody("<html>login</html>"))
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        get().use { assertEquals("{\"ok\":true}", it.body!!.string()) }
        assertEquals(1, source.invalidated)
        server.takeRequest(); server.takeRequest()
        assertEquals("Bearer t2", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun retriesOnceOn401() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("{}"))
        get().use { assertEquals(200, it.code) }
        assertEquals(1, source.invalidated)
    }

    @Test fun neverLoopsSecondRedirectThrowsSignInRequired() {
        server.enqueue(loginRedirect()); server.enqueue(MockResponse().setBody("<html/>"))
        server.enqueue(loginRedirect()); server.enqueue(MockResponse().setBody("<html/>"))
        try { get().close(); fail("expected SignInRequiredException") } catch (e: SignInRequiredException) { }
        assertEquals(1, source.invalidated)
        assertEquals(4, server.requestCount)
    }

    @Test fun signedOutRedirectThrowsSignInRequired() {
        source.signedIn = false
        server.enqueue(loginRedirect()); server.enqueue(MockResponse().setBody("<html/>"))
        try { get().close(); fail("expected SignInRequiredException") } catch (e: SignInRequiredException) { }
    }

    @Test fun adminRequired403BecomesAdminRequiredException() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"ok":false,"error":"admin required"}"""))
        val api = RadioApi({ InMemoryRadioSettings(baseUrl = server.url("").toString().trimEnd('/')) }, client)
        try {
            api.tune(TuneRequest(freq = 100.7, band = Band.FM))
            fail("expected AdminRequiredException")
        } catch (e: AdminRequiredException) {
            assertEquals(ADMIN_MESSAGE, e.message)
        }
        assertTrue(server.requestCount >= 1)
    }
}
