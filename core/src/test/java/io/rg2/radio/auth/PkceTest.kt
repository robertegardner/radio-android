package io.rg2.radio.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PkceTest {
    @Test fun challengeMatchesRfc7636Example() {
        // RFC 7636 Appendix B
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun verifierIsUrlSafeAndLongEnough() {
        val v = Pkce.newVerifier()
        assertTrue(v.length in 43..128)
        assertTrue(v.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(v, Pkce.newVerifier())
    }

    @Test fun authorizeUrlCarriesPkceAndScopes() {
        val url = Pkce.authorizeUrl(AuthConfig.DEFAULT, "chal", "st8").toHttpUrl()
        assertEquals("authentik.bobgardner.org", url.host)
        assertEquals("/application/o/authorize/", url.encodedPath)
        assertEquals("hemTnLT7eGgimzjqizkXFTGlWI0phGmjPaLaWm0O", url.queryParameter("client_id"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("io.rg2.radio:/oauth2redirect", url.queryParameter("redirect_uri"))
        assertEquals("openid profile email offline_access", url.queryParameter("scope"))
        assertEquals("chal", url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("st8", url.queryParameter("state"))
    }

    @Test fun hostMapCoversTheThreeAppHosts() {
        assertEquals(setOf("radio.rg2.io", "ems.rg2.io", "icecast.rg2.io"),
            AuthConfig.DEFAULT.hostClientIds.keys)
    }

    @Test fun parsesUsernameAndGroupsFromJwt() {
        val payload = """{"preferred_username":"kid","groups":["family","users"]}"""
        val jwt = "e30." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray()) + ".sig"
        assertEquals(JwtClaims("kid", listOf("family", "users")), JwtClaims.parse(jwt))
    }

    @Test fun malformedJwtParsesToNull() {
        assertNull(JwtClaims.parse("not-a-jwt"))
        assertNull(JwtClaims.parse("a.%%%.c"))
    }
}
