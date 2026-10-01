package io.rg2.radio.auth

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OAuthClientTest {
    private val server = MockWebServer()
    private lateinit var client: OAuthClient

    @Before fun setUp() {
        server.start()
        val cfg = AuthConfig.DEFAULT.copy(baseUrl = server.url("").toString().trimEnd('/'))
        client = OAuthClient(cfg, OkHttpClient())
    }

    @After fun tearDown() = server.shutdown()

    private fun form(): Map<String, String> = server.takeRequest().body.readUtf8()
        .split("&").associate { val (k, v) = it.split("=", limit = 2); k to java.net.URLDecoder.decode(v, "UTF-8") }

    @Test fun redeemCodePostsPkceForm() {
        server.enqueue(MockResponse().setBody("""{"access_token":"at","refresh_token":"rt","id_token":"it","expires_in":3600}"""))
        val r = client.redeemCode("c0de", "ver")
        assertEquals(OAuthResult.Ok(TokenResponse("at", "rt", "it", 3600)), r)
        val f = form()
        assertEquals("authorization_code", f["grant_type"])
        assertEquals("c0de", f["code"])
        assertEquals("ver", f["code_verifier"])
        assertEquals("io.rg2.radio:/oauth2redirect", f["redirect_uri"])
        assertEquals("hemTnLT7eGgimzjqizkXFTGlWI0phGmjPaLaWm0O", f["client_id"])
    }

    @Test fun exchangeUsesJwtBearerAssertionAndProxyScopes() {
        server.enqueue(MockResponse().setBody("""{"access_token":"host","expires_in":86400}"""))
        assertEquals(OAuthResult.Ok(TokenResponse("host", expiresIn = 86400)), client.exchange("login", "HOSTCID"))
        val req = server.takeRequest()
        assertEquals("/application/o/token/", req.path)
        val f = req.body.readUtf8().split("&").associate { val (k, v) = it.split("=", limit = 2); k to java.net.URLDecoder.decode(v, "UTF-8") }
        assertEquals("client_credentials", f["grant_type"])
        assertEquals("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", f["client_assertion_type"])
        assertEquals("login", f["client_assertion"])
        assertEquals("HOSTCID", f["client_id"])
        assertEquals("openid profile email ak_proxy", f["scope"])
    }

    @Test fun oauthErrorBodyMapsToErr() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"x"}"""))
        assertEquals(OAuthResult.Err("invalid_grant"), client.refresh("rt"))
    }

    @Test fun nonJsonErrorMapsToHttpCode() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>"))
        assertEquals(OAuthResult.Err("http_502"), client.refresh("rt"))
    }

    @Test fun networkFailureMapsToNetwork() {
        server.shutdown()
        assertTrue(client.refresh("rt") is OAuthResult.Network)
    }

    @Test fun deviceAuthorizeAndPoll() {
        server.enqueue(MockResponse().setBody("""{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://a/device","verification_uri_complete":"https://a/device?code=ABCD","expires_in":600,"interval":5}"""))
        val d = (client.deviceAuthorize() as OAuthResult.Ok).value
        assertEquals("ABCD-1234", d.userCode)
        assertEquals("/application/o/device/", server.takeRequest().path)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"authorization_pending"}"""))
        assertEquals(OAuthResult.Err("authorization_pending"), client.pollDevice("dc"))
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", form()["grant_type"])
    }

    @Test fun revokePostsToken() {
        server.enqueue(MockResponse().setResponseCode(200))
        client.revoke("rt")
        val req = server.takeRequest()
        assertEquals("/application/o/revoke/", req.path)
        assertTrue(req.body.readUtf8().contains("token=rt"))
    }

    @Test fun successWithNonJsonBodyIsInvalidResponse() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>portal</html>"))
        assertEquals(OAuthResult.Err("invalid_response"), client.refresh("rt"))
    }
}
