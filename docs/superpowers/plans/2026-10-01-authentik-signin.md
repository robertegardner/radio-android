# Authentik Sign-in (phone + Wear) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Family members sign in once with Authentik on the phone (PKCE) and on the Wear watch (device code). The apps then authenticate every request to radio/ems/icecast through a per-host token exchange, so they keep working off-LAN after the platform gates those hosts.

**Architecture:**
- **Platform first.** The registry and `radio-auth.py` federate the existing `radio-android` OAuth2 client into every radio proxy provider, and a brand device-code flow is enabled.
- **App (`core`, pure Kotlin, JVM-tested):** PKCE helpers, an Authentik HTTP client, a session repository, a per-host exchange cache, an OkHttp interceptor, and a device-code poller.
- **Phone and watch:** each wires these into its existing container, its ExoPlayer (via `OkHttpDataSource`) and a small Account UI.

**Tech Stack:** Kotlin 2.1.10, OkHttp 4.12.0, kotlinx.serialization 1.8.0, coroutines 1.10.1, media3 1.5.1, JUnit 4 + MockWebServer + coroutines-test. Platform side: stdlib Python 3.

**Spec:** `docs/superpowers/specs/2026-10-01-authentik-signin-design.md` (radio-android repo).
Platform companion: `~/projects/platform-radio-auth` (branch `feat/radio-authentik-access`, PR #46).

## Global Constraints

- Authentik public base: `https://authentik.bobgardner.org`.
  - Token endpoint `/application/o/token/`, device endpoint `/application/o/device/`, revoke endpoint `/application/o/revoke/`.
  - Authorize URL `/application/o/authorize/`.
  - Device verification page `https://authentik.bobgardner.org/device`.
- `radio-android` client_id: `hemTnLT7eGgimzjqizkXFTGlWI0phGmjPaLaWm0O`. Redirect URI `io.rg2.radio:/oauth2redirect`.
- Login scopes: `openid profile email offline_access`. Exchange scopes: `openid profile email ak_proxy`. Without these, backends get no identity.
- Exchange request form fields:
  - `grant_type=client_credentials`
  - `client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer`
  - `client_assertion=<login access token>`
  - `client_id=<host client_id>`
  - `scope=<exchange scopes>`
- Host map:
  - `radio.rg2.io` → `EejQH8q8sJLXZIdRiCkeq9L5Ytn6xTzB0x84f9jU`
  - `ems.rg2.io` → `qMx0qI2CruW3bSQK8aC4T2ytq2xSmX0jaP5wyW5p`
  - `icecast.rg2.io` → `0udNEfQq1J7cKJO5JequfHA4YEPDWGe6wZIRFXlK`
- Admin group name: `homelab-admin`. A host token is refreshed when it has fewer than **5 minutes** left.
- **HTTPS only**; no cleartext exceptions. Never commit secrets. House style: no DI framework and no Retrofit, with minimal deps.
- Authentik provider PATCHes must always include `"mode": "forward_single"`; otherwise the serializer 400s.
- Platform: never `terraform fmt -recursive`. Commits end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Android build/test: run from `~/radio-android-auth` with `./gradlew --no-daemon -q …`. `local.properties` must exist (copy it from `~/radio-android`). No device is attached to codeserver, so the user does device checks via sideload.

## Plan-time refinements to the spec (recorded; spec amended in Task 0)

1. **PKCE is hand-rolled** (OkHttp plus a browser `ACTION_VIEW` intent) instead of AppAuth/Custom Tabs. That means no new dependency, and the whole login logic is JVM-testable.
2. **Token storage:** Android Keystore AES-GCM over SharedPreferences, instead of DataStore + Tink. No new dependency.
3. **Phone UI:** an **ACCOUNT tab** in the existing bottom tab bar, instead of a gear plus a sheet on the 1000-line Now Playing screen.
4. **Watch:** the device-code screen shows the user code and URL as **text, without a QR code** (no QR dependency).

## Review Focus

1. **Redirect handling:** OkHttp follows the outpost's 302 automatically, so an application interceptor only ever sees the final HTML 200. Re-auth detection must walk `priorResponse`. Test: `retriesOnceWhenRedirectedToOutpost`.
2. **Rotating refresh tokens:** a refresh that succeeds but isn't persisted strands the session on the next refresh. Test: `refreshPersistsRotatedRefreshToken`.
3. **Concurrent first requests** (the ~1 s poll plus a stream start) can stampede the exchange. `HostTokenCache.tokenFor` must be synchronized so it exchanges once. Test: `concurrentCallersExchangeOnce`.
4. **Signed out and off-LAN must surface a clear error, not a JSON parse error.** A request that lands on the login page with no token must throw `SignInRequiredException`. Test: `signedOutRedirectThrowsSignInRequired`.
5. **Federation convergence on the platform must only add.** Federation pks set by hand on a provider must survive. Test: `test_federation_union_keeps_foreign`.

---

## File Structure

**platform** (`~/projects/platform-radio-auth`, branch `feat/radio-authentik-access`)
- Modify `tools/radio_auth_core.py`: add `provider_patch()`, `oauth_client_patch()` and `load_registry` validation for `federate` and `oauth_clients`.
- Modify `tools/radio-auth.py`: federation convergence in `ensure_host`, plus `ensure_oauth_clients()`.
- Modify `terraform/registry/auth.json`: add `federate` and `oauth_clients`.
- Modify `tools/tests/test_radio_auth_core.py`.
- Modify `docs/deployment_notes.md` (runbook: device-code flow).

**radio-android** (`~/radio-android-auth`, branch `feat/authentik-signin`)
- `gradle/libs.versions.toml`, `core/build.gradle.kts`, `app/build.gradle.kts`, `wear/build.gradle.kts`: dependencies.
- `core/src/main/java/io/rg2/radio/auth/` (new package):
  - `AuthConfig.kt`: constants and the host map.
  - `Pkce.kt`: verifier, challenge and authorize URL; also `JwtClaims`.
  - `OAuthClient.kt`: blocking Authentik HTTP calls (redeem, refresh, exchange, revoke, device), returning `OAuthResult`.
  - `AuthState.kt`: the serializable persisted session, plus the `TokenStore` interface and `InMemoryTokenStore`.
  - `AuthRepository.kt`: session state machine, refresh and sign-out.
  - `HostTokenCache.kt`: per-host exchange, the cache and `NoAccess`.
  - `AuthInterceptor.kt`: OkHttp interceptor, `SignInRequiredException`.
  - `DeviceCodeLogin.kt`: device-code polling.
  - `KeystoreTokenStore.kt`: the Android Keystore-backed `TokenStore` (not JVM-tested).
- `core/src/main/java/io/rg2/radio/data/`:
  - `RadioSettings.kt`: drop `authHeader`.
  - `RadioApi.kt`: `defaultClient(interceptor)`, `AdminRequiredException`, no Basic header.
  - `ScannerApi.kt`: no Basic header; 403 maps to the admin message.
- `core/src/test/java/io/rg2/radio/auth/*Test.kt`.
- App:
  - `app/.../RadioApp.kt`: wire auth.
  - `app/.../auth/AuthRedirectActivity.kt`.
  - `app/.../ui/AccountScreen.kt`.
  - `app/.../MainActivity.kt`: ACCOUNT tab.
  - `app/.../playback/PlaybackService.kt`: OkHttp data source, sign-in hint and the Auto item.
  - `app/.../ui/NowPlayingViewModel.kt` + `NowPlayingScreen.kt`: action message, and the auth banner.
  - `app/src/main/AndroidManifest.xml`.
- Wear:
  - `wear/.../WearApp.kt`.
  - `wear/.../playback/WearPlaybackService.kt`.
  - `wear/.../ui/WearAccountScreen.kt`.
  - `wear/.../ui/WearHomeScreen.kt`: Account chip and screen switch.

---

### Task 0: Amend the spec with the plan-time refinements

**Files:** Modify `docs/superpowers/specs/2026-10-01-authentik-signin-design.md`.

- [ ] **Step 1:** Append the following section to the end of the spec:

```markdown
## Plan-time refinements (2026-10-01, from the implementation plan)

1. PKCE is hand-rolled (OkHttp + a browser `ACTION_VIEW` intent; redirect caught by
   `AuthRedirectActivity`) instead of AppAuth/Custom Tabs — no new dependency and the
   login logic is JVM-testable.
2. Tokens are stored with an Android Keystore AES-GCM key over SharedPreferences
   (`KeystoreTokenStore`) instead of DataStore + Tink — no new dependency.
3. Phone Account UI is an ACCOUNT tab in the existing bottom tab bar, not a gear + sheet.
4. The watch device-code screen shows the user code + URL as text; no QR code.
```

- [ ] **Step 2: Commit**

```bash
cd ~/radio-android-auth && git add docs/superpowers/specs/2026-10-01-authentik-signin-design.md && git commit -m "spec: plan-time refinements (hand-rolled PKCE, keystore store, ACCOUNT tab, no QR)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 1: Platform — federation + oauth-client convergence in `radio_auth_core`

**Files:**
- Modify: `~/projects/platform-radio-auth/tools/radio_auth_core.py`
- Modify: `~/projects/platform-radio-auth/terraform/registry/auth.json`
- Test: `~/projects/platform-radio-auth/tools/tests/test_radio_auth_core.py`

**Interfaces:**
- Produces: `provider_patch(fields: dict) -> dict` (adds `"mode": "forward_single"`); `oauth_client_patch(live: dict, want: dict) -> dict | None`.
- Registry keys: `federate: list[str]`, and `oauth_clients: {name: {"grant_types": [...], "access": "listen"|"admin"}}`.
- Federation union reuses the existing `outpost_union(current, needed)`.

- [ ] **Step 1: Add the registry keys.** In `terraform/registry/auth.json`, after `"app_group": "Radio Platform",`, insert:

```json
  "federate": ["radio-android"],
  "oauth_clients": {
    "radio-android": {
      "access": "listen",
      "grant_types": ["authorization_code", "refresh_token", "urn:ietf:params:oauth:grant-type:device_code"]
    }
  },
```

- [ ] **Step 2: Write the failing tests.** Append this class before `if __name__ == "__main__":` in `tools/tests/test_radio_auth_core.py`:

```python
class AndroidSigninTest(unittest.TestCase):
    def test_registry_has_federation_and_client(self):
        reg = core.load_registry(REGISTRY)
        self.assertEqual(reg["federate"], ["radio-android"])
        self.assertEqual(reg["oauth_clients"]["radio-android"]["access"], "listen")

    def test_federate_must_name_an_oauth_client(self):
        reg = json.load(open(REGISTRY))
        reg["federate"] = ["nope"]
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            json.dump(reg, fh)
        with self.assertRaises(ValueError):
            core.load_registry(fh.name)

    def test_provider_patch_always_sends_mode(self):
        self.assertEqual(core.provider_patch({"jwt_federation_providers": [94]}),
                         {"jwt_federation_providers": [94], "mode": "forward_single"})

    def test_federation_union_keeps_foreign(self):
        self.assertEqual(core.outpost_union([12, 94], [94]), [12, 94])
        self.assertEqual(core.outpost_union([12], [94]), [12, 94])

    def test_oauth_client_patch_sets_missing_grants(self):
        want = {"grant_types": ["authorization_code", "refresh_token"]}
        self.assertEqual(core.oauth_client_patch({"grant_types": []}, want),
                         {"grant_types": ["authorization_code", "refresh_token"]})

    def test_oauth_client_patch_none_when_converged_in_any_order(self):
        want = {"grant_types": ["authorization_code", "refresh_token"]}
        self.assertIsNone(core.oauth_client_patch(
            {"grant_types": ["refresh_token", "authorization_code"]}, want))
```

- [ ] **Step 3: Run, expect FAIL**

Run: `cd ~/projects/platform-radio-auth && python3 -m unittest discover -s tools/tests 2>&1 | tail -3`
Expected: errors on `KeyError: 'federate'` / `AttributeError: ... 'provider_patch'`.

- [ ] **Step 4: Implement.** In `tools/radio_auth_core.py`, add after `binding_changes`:

```python
def provider_patch(fields):
    """Authentik re-validates `mode` on any proxy-provider PATCH (400 without it)."""
    return {**fields, "mode": "forward_single"}


def oauth_client_patch(live, want):
    """Grant types for an OAuth2 client. API-created clients start with [] and
    then fail every grant with invalid_grant (2026-10-01 spike)."""
    if set(live.get("grant_types") or []) == set(want["grant_types"]):
        return None
    return {"grant_types": list(want["grant_types"])}
```

In `load_registry`, add the following just before `return reg`:

```python
    clients = reg.setdefault("oauth_clients", {})
    for name, c in clients.items():
        if c.get("access") not in ACCESS_LEVELS:
            raise ValueError(f"oauth client {name}: access must be one of {ACCESS_LEVELS}")
        if not c.get("grant_types"):
            raise ValueError(f"oauth client {name}: grant_types required")
    for name in reg.setdefault("federate", []):
        if name not in clients:
            raise ValueError(f"federate {name!r} is not a registry oauth_client")
```

- [ ] **Step 5: Run, expect PASS**

Run: `cd ~/projects/platform-radio-auth && python3 -m unittest discover -s tools/tests 2>&1 | tail -1`
Expected: `OK` (32 tests).

- [ ] **Step 6: Commit**

```bash
cd ~/projects/platform-radio-auth && git add tools/radio_auth_core.py tools/tests/test_radio_auth_core.py terraform/registry/auth.json && git commit -m "radio-auth core: federation + oauth-client grant convergence; provider PATCH carries mode

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Platform — `radio-auth.py` applies federation, client grants and bindings; brand device flow

**Files:**
- Modify: `~/projects/platform-radio-auth/tools/radio-auth.py`
- Modify: `~/projects/platform-radio-auth/docs/deployment_notes.md`

**Interfaces:**
- Consumes: `core.provider_patch`, `core.oauth_client_patch`, `core.outpost_union`, `core.binding_changes`, `core.pick_exact` (Task 1 plus earlier work).
- Produces: live state where every registry host provider federates pk 94, `radio-android` has its grant types and bindings, and the brand has a device-code flow.

- [ ] **Step 1: Route the drift PATCH through `provider_patch`.** In `Authentik.ensure_host`, replace

```python
                    ak(f"providers/proxy/{prov['pk']}/", "PATCH", drift)
```

with

```python
                    ak(f"providers/proxy/{prov['pk']}/", "PATCH", core.provider_patch(drift))
```

- [ ] **Step 2: Converge federation in `ensure_host`.** Directly after the `pk = prov["pk"] if prov else None` line, insert:

```python
        if prov and self.federate_pks:
            have = prov.get("jwt_federation_providers") or []
            merged = core.outpost_union(have, self.federate_pks)
            if merged != have:
                print(f"  ~ federation {name}: +{[p for p in merged if p not in have]}")
                if not self.dry:
                    ak(f"providers/proxy/{prov['pk']}/", "PATCH",
                       core.provider_patch({"jwt_federation_providers": merged}))
```

At the end of `Authentik.__init__`, add:

```python
        self.oauth = {}
        for cname in reg.get("oauth_clients", {}):
            p = ak_one("providers/oauth2/", "name", cname, search=cname)
            if not p:
                sys.exit(f"oauth2 provider {cname!r} not found (create it first; see spec)")
            self.oauth[cname] = p
        self.federate_pks = [self.oauth[n]["pk"] for n in reg.get("federate", [])]
```

- [ ] **Step 3: Add `ensure_oauth_clients`** as a method of `Authentik`:

```python
    def ensure_oauth_clients(self):
        for cname, want in self.reg.get("oauth_clients", {}).items():
            p = self.oauth[cname]
            patch = core.oauth_client_patch(p, want)
            if patch:
                print(f"  ~ oauth client {cname}: grant_types -> {patch['grant_types']}")
                if not self.dry:
                    ak(f"providers/oauth2/{p['pk']}/", "PATCH", patch)
            app = ak_app(cname)
            if not app:
                sys.exit(f"application {cname!r} missing for oauth client")
            have = {b["group_obj"]["name"]: b["pk"]
                    for b in ak("policies/bindings/", params={"target": app["pk"]})["results"]
                    if b.get("group") and b.get("target") == app["pk"]}
            desired = core.binding_groups(want, self.reg["groups"])
            managed = {g for names in self.reg["groups"].values() for g in names}
            add, remove = core.binding_changes(have, desired, managed)
            for g in add:
                print(f"  + binding {cname} -> group {g}")
                if not self.dry:
                    ak("policies/bindings/", "POST",
                       {"target": app["pk"], "group": self.groups[g], "order": desired.index(g),
                        "enabled": True, "negate": False, "timeout": 30})
            for bpk in remove:
                print(f"  - binding {cname} -> {next(g for g, b in have.items() if b == bpk)}")
                if not self.dry:
                    ak(f"policies/bindings/{bpk}/", "DELETE")
```

In `main()`, inside `if "--npm-only" not in args:`, call `a.ensure_oauth_clients()` right after `a = Authentik(reg, dry)`.

- [ ] **Step 4: Static check, unit suite and live plan**

Run: `cd ~/projects/platform-radio-auth && python3 -m py_compile tools/radio-auth.py && python3 -m unittest discover -s tools/tests 2>&1 | tail -1 && set -a && . ~/.config/npm-proxy.env && . ~/.config/radio-auth.env && set +a && python3 tools/radio-auth.py plan --authentik-only | grep -E "^\s+[~+-]"`
Expected: `OK`, then 12 lines `~ federation radio-auth: <host>: +[94]`. There should be no grant-types line (already set during the spike) and no binding lines.

- [ ] **Step 5: Apply and re-plan**

Run: `python3 tools/radio-auth.py apply --authentik-only | grep -E "^\s+[~+-]" | wc -l && python3 tools/radio-auth.py plan --authentik-only | grep -cE "^\s+[~+-]"`
Expected: `12`, then `0`.

- [ ] **Step 6: Device-code flow on the brand (one-time, via the API).**

```bash
set -a; . ~/.config/radio-auth.env; set +a; H="Authorization: Bearer $AUTHENTIK_TOKEN"; A="$AUTHENTIK_URL/api/v3"
FLOW=$(curl -sk -H "$H" "$A/flows/instances/?slug=default-device-code" | python3 -c "import sys,json;r=json.load(sys.stdin)['results'];print(r[0]['pk'] if r else '')")
[ -z "$FLOW" ] && FLOW=$(curl -sk -H "$H" -H "Content-Type: application/json" -X POST "$A/flows/instances/" -d '{"name":"Device code","slug":"default-device-code","title":"Enter your device code","designation":"stage_configuration","authentication":"require_authenticated"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['pk'])")
BR=$(curl -sk -H "$H" "$A/core/brands/" | python3 -c "import sys,json;print([b for b in json.load(sys.stdin)['results'] if b['default']][0]['brand_uuid'])")
curl -sk -H "$H" -H "Content-Type: application/json" -X PATCH "$A/core/brands/$BR/" -d "{\"flow_device_code\":\"$FLOW\"}" | python3 -c "import sys,json;print('brand flow_device_code:',json.load(sys.stdin)['flow_device_code'])"
curl -sk "$AUTHENTIK_URL/application/o/device/" -H "Host: authentik.bobgardner.org" -H "X-Forwarded-Proto: https" -d client_id=hemTnLT7eGgimzjqizkXFTGlWI0phGmjPaLaWm0O -d "scope=openid profile email offline_access" | python3 -c "import sys,json;d=json.load(sys.stdin);print('device endpoint:', sorted(k for k in d if k!='device_code'), d.get('verification_uri'))"
```

Expected: `brand flow_device_code: <uuid>`, then `device endpoint: ['expires_in', 'interval', 'user_code', 'verification_uri', 'verification_uri_complete']` with `https://authentik.bobgardner.org/device`. If the device endpoint errors, read `events/events/?ordering=-created` for the reason and fix it before continuing. The watch can't sign in without this.

- [ ] **Step 7: Runbook.** In `docs/deployment_notes.md`, in the "Radio access control runbook" section, add this bullet after the "Add a host" bullet:

```markdown
- **App sign-in (radio-android, 2026-10-01):** OAuth2 public client `radio-android`
  (pk 94) is federated into every registry host provider (`federate` in auth.json);
  apps exchange their login token per host (client_assertion jwt-bearer, scopes
  `openid profile email ak_proxy`). API-created OAuth2 clients start with
  `grant_types: []` → registry `oauth_clients` converges them. Device-code login (Wear)
  needs the default brand's `flow_device_code` = flow `default-device-code`
  (designation stage_configuration, require_authenticated) — set 2026-10-01 via API,
  not managed by radio-auth.py.
```

- [ ] **Step 8: Commit + push**

```bash
cd ~/projects/platform-radio-auth && git add tools/radio-auth.py docs/deployment_notes.md && git commit -m "radio-auth: federate radio-android into host providers, converge oauth client grants/bindings; runbook device-code flow

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" && git push -q
```

---

### Task 3: Android — test infrastructure + `AuthConfig`, `Pkce`, `JwtClaims`

**Files:**
- Modify: `gradle/libs.versions.toml`, `core/build.gradle.kts`
- Create: `core/src/main/java/io/rg2/radio/auth/AuthConfig.kt`, `core/src/main/java/io/rg2/radio/auth/Pkce.kt`
- Test: `core/src/test/java/io/rg2/radio/auth/PkceTest.kt`

**Interfaces:**
- Produces: `data class AuthConfig(baseUrl: String, clientId: String, redirectUri: String, loginScopes: String, exchangeScopes: String, hostClientIds: Map<String,String>, adminGroup: String)` with `companion val DEFAULT`, plus `tokenUrl`, `authorizeUrl`, `deviceUrl` and `revokeUrl` properties.
- `object Pkce { fun newVerifier(): String; fun challenge(verifier: String): String; fun newState(): String; fun authorizeUrl(config: AuthConfig, challenge: String, state: String): String }`.
- `data class JwtClaims(username: String, groups: List<String>)` with `companion fun parse(jwt: String): JwtClaims?`.

- [ ] **Step 1: Dependencies.** In `gradle/libs.versions.toml`, under `[versions]`, add `junit = "4.13.2"`. Under `[libraries]`, add:

```toml
junit = { group = "junit", name = "junit", version.ref = "junit" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
media3-datasource-okhttp = { group = "androidx.media3", name = "media3-datasource-okhttp", version.ref = "media3" }
```

In `core/build.gradle.kts`, change the `dependencies` block to:

```kotlin
dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 2: Write the failing test** `core/src/test/java/io/rg2/radio/auth/PkceTest.kt`:

```kotlin
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
```

- [ ] **Step 3: Run, expect FAIL** (compile errors: unresolved `Pkce`/`AuthConfig`/`JwtClaims`)

Run: `cd ~/radio-android-auth && ./gradlew --no-daemon -q :core:testDebugUnitTest --tests 'io.rg2.radio.auth.PkceTest' 2>&1 | grep -E "Unresolved|error:|FAIL" | head -5`
Expected: `Unresolved reference 'Pkce'` (or similar).

- [ ] **Step 4: Implement** `core/src/main/java/io/rg2/radio/auth/AuthConfig.kt`:

```kotlin
package io.rg2.radio.auth

/**
 * Authentik sign-in constants (spec 2026-10-01-authentik-signin). The host map is
 * the platform registry's per-host proxy-provider client_ids — the app exchanges
 * its login token for a token issued by each host's own provider (the outpost
 * only accepts those). Changes only if a provider is recreated.
 */
data class AuthConfig(
    val baseUrl: String,
    val clientId: String,
    val redirectUri: String,
    val loginScopes: String,
    val exchangeScopes: String,
    val hostClientIds: Map<String, String>,
    val adminGroup: String,
) {
    val tokenUrl get() = "$baseUrl/application/o/token/"
    val authorizeUrl get() = "$baseUrl/application/o/authorize/"
    val deviceUrl get() = "$baseUrl/application/o/device/"
    val revokeUrl get() = "$baseUrl/application/o/revoke/"

    companion object {
        val DEFAULT = AuthConfig(
            baseUrl = "https://authentik.bobgardner.org",
            clientId = "hemTnLT7eGgimzjqizkXFTGlWI0phGmjPaLaWm0O",
            redirectUri = "io.rg2.radio:/oauth2redirect",
            loginScopes = "openid profile email offline_access",
            // profile/email/ak_proxy are what make the outpost forward
            // X-authentik-username/groups to the backend (spike 2026-10-01).
            exchangeScopes = "openid profile email ak_proxy",
            hostClientIds = mapOf(
                "radio.rg2.io" to "EejQH8q8sJLXZIdRiCkeq9L5Ytn6xTzB0x84f9jU",
                "ems.rg2.io" to "qMx0qI2CruW3bSQK8aC4T2ytq2xSmX0jaP5wyW5p",
                "icecast.rg2.io" to "0udNEfQq1J7cKJO5JequfHA4YEPDWGe6wZIRFXlK",
            ),
            adminGroup = "homelab-admin",
        )
    }
}
```

Then `core/src/main/java/io/rg2/radio/auth/Pkce.kt`:

```kotlin
package io.rg2.radio.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 7636 PKCE helpers for the browser sign-in. */
object Pkce {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun newVerifier(): String = b64.encodeToString(ByteArray(32).also(random::nextBytes))

    fun newState(): String = b64.encodeToString(ByteArray(16).also(random::nextBytes))

    fun challenge(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizeUrl(config: AuthConfig, challenge: String, state: String): String =
        config.authorizeUrl.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", config.clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", config.redirectUri)
            .addQueryParameter("scope", config.loginScopes)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .build().toString()
}

/** Display-only identity from an ID token (the server stays the authority). */
data class JwtClaims(val username: String, val groups: List<String>) {
    companion object {
        fun parse(jwt: String): JwtClaims? = runCatching {
            val payload = jwt.split(".")[1]
            val obj = Json.parseToJsonElement(
                String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8),
            ).jsonObject
            JwtClaims(
                username = obj["preferred_username"]!!.jsonPrimitive.content,
                groups = obj["groups"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            )
        }.getOrNull()
    }
}
```

- [ ] **Step 5: Run, expect PASS**

Run: `cd ~/radio-android-auth && ./gradlew --no-daemon -q :core:testDebugUnitTest --tests 'io.rg2.radio.auth.PkceTest' && echo PASS`
Expected: `PASS`

- [ ] **Step 6: Commit**

```bash
cd ~/radio-android-auth && git add gradle/libs.versions.toml core/build.gradle.kts core/src/main/java/io/rg2/radio/auth core/src/test && git commit -m "auth: test infra + AuthConfig/Pkce/JwtClaims

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Android — `OAuthClient` (blocking Authentik calls)

**Files:**
- Create: `core/src/main/java/io/rg2/radio/auth/OAuthClient.kt`
- Test: `core/src/test/java/io/rg2/radio/auth/OAuthClientTest.kt`

**Interfaces:**
- Consumes: `AuthConfig` (Task 3).
- Produces:

```kotlin
@Serializable data class TokenResponse(accessToken: String, refreshToken: String? = null, idToken: String? = null, expiresIn: Long = 3600)
@Serializable data class DeviceAuthorization(deviceCode: String, userCode: String, verificationUri: String, verificationUriComplete: String? = null, expiresIn: Long = 600, interval: Long = 5)
sealed interface OAuthResult<out T> { data class Ok<T>(val value: T); data class Err(val error: String); data class Network(val cause: java.io.IOException) }
class OAuthClient(config: AuthConfig, http: OkHttpClient) {
  fun redeemCode(code: String, verifier: String): OAuthResult<TokenResponse>
  fun refresh(refreshToken: String): OAuthResult<TokenResponse>
  fun exchange(loginAccessToken: String, hostClientId: String): OAuthResult<TokenResponse>
  fun revoke(refreshToken: String)
  fun deviceAuthorize(): OAuthResult<DeviceAuthorization>
  fun pollDevice(deviceCode: String): OAuthResult<TokenResponse>
}
```

`Err.error` is the OAuth `error` field (`invalid_grant`, `authorization_pending`, `slow_down`, `expired_token`, `access_denied`), or `http_<code>` when no JSON error is present.

- [ ] **Step 1: Write the failing test** `core/src/test/java/io/rg2/radio/auth/OAuthClientTest.kt`:

```kotlin
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
}
```

- [ ] **Step 2: Run, expect FAIL** (unresolved `OAuthClient`)

Run: `cd ~/radio-android-auth && ./gradlew --no-daemon -q :core:testDebugUnitTest --tests 'io.rg2.radio.auth.OAuthClientTest' 2>&1 | grep -E "Unresolved" | head -2`
Expected: `Unresolved reference 'OAuthClient'`.

- [ ] **Step 3: Implement** `core/src/main/java/io/rg2/radio/auth/OAuthClient.kt`:

```kotlin
package io.rg2.radio.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

@Serializable
data class DeviceAuthorization(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 600,
    val interval: Long = 5,
)

sealed interface OAuthResult<out T> {
    data class Ok<T>(val value: T) : OAuthResult<T>
    /** OAuth `error` code, or `http_<status>` when the body isn't an OAuth error. */
    data class Err(val error: String) : OAuthResult<Nothing>
    data class Network(val cause: IOException) : OAuthResult<Nothing>
}

/**
 * Blocking calls to Authentik's OAuth endpoints. Uses its own [http] client
 * WITHOUT [AuthInterceptor] (the interceptor calls into this; sharing would recurse).
 */
class OAuthClient(private val config: AuthConfig, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    fun redeemCode(code: String, verifier: String) = token(
        "grant_type" to "authorization_code", "code" to code, "code_verifier" to verifier,
        "redirect_uri" to config.redirectUri, "client_id" to config.clientId,
    )

    fun refresh(refreshToken: String) = token(
        "grant_type" to "refresh_token", "refresh_token" to refreshToken,
        "client_id" to config.clientId,
    )

    fun exchange(loginAccessToken: String, hostClientId: String) = token(
        "grant_type" to "client_credentials",
        "client_assertion_type" to "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
        "client_assertion" to loginAccessToken,
        "client_id" to hostClientId,
        "scope" to config.exchangeScopes,
    )

    fun pollDevice(deviceCode: String) = token(
        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        "device_code" to deviceCode, "client_id" to config.clientId,
    )

    fun deviceAuthorize(): OAuthResult<DeviceAuthorization> =
        post(config.deviceUrl, listOf("client_id" to config.clientId, "scope" to config.loginScopes)) {
            json.decodeFromString(DeviceAuthorization.serializer(), it)
        }

    /** Best effort — a failed revoke must never block local sign-out. */
    fun revoke(refreshToken: String) {
        runCatching {
            post(config.revokeUrl, listOf("token" to refreshToken, "client_id" to config.clientId)) { it }
        }
    }

    private fun token(vararg fields: Pair<String, String>): OAuthResult<TokenResponse> =
        post(config.tokenUrl, fields.toList()) { json.decodeFromString(TokenResponse.serializer(), it) }

    private fun <T> post(url: String, fields: List<Pair<String, String>>, parse: (String) -> T): OAuthResult<T> {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return try {
            http.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    OAuthResult.Ok(parse(text))
                } else {
                    val err = runCatching {
                        json.parseToJsonElement(text).jsonObject["error"]!!.jsonPrimitive.content
                    }.getOrNull()
                    OAuthResult.Err(err ?: "http_${resp.code}")
                }
            }
        } catch (e: IOException) {
            OAuthResult.Network(e)
        }
    }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `cd ~/radio-android-auth && ./gradlew --no-daemon -q :core:testDebugUnitTest --tests 'io.rg2.radio.auth.OAuthClientTest' && echo PASS`
Expected: `PASS`

- [ ] **Step 5: Commit**

```bash
cd ~/radio-android-auth && git add core/src && git commit -m "auth: OAuthClient (redeem/refresh/exchange/revoke/device) with MockWebServer tests

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Android — `AuthState`/`TokenStore` + `AuthRepository`

**Files:**
- Create: `core/src/main/java/io/rg2/radio/auth/AuthState.kt`, `core/src/main/java/io/rg2/radio/auth/AuthRepository.kt`
- Test: `core/src/test/java/io/rg2/radio/auth/AuthRepositoryTest.kt`

**Interfaces:**
- Consumes: `OAuthClient`, `TokenResponse`, `OAuthResult`, `JwtClaims`, `AuthConfig`.
- Produces:

```kotlin
@Serializable data class HostToken(val token: String, val expiresAtMs: Long)
@Serializable data class AuthState(refreshToken: String, accessToken: String, accessExpiresAtMs: Long, username: String, groups: List<String>, hostTokens: Map<String, HostToken> = emptyMap())
interface TokenStore { fun load(): AuthState?; fun save(state: AuthState?) }
class InMemoryTokenStore(var state: AuthState? = null) : TokenStore
sealed interface AuthStatus { object SignedOut; data class SignedIn(username, groups, isAdmin: Boolean); object Expired }
class AuthRepository(config, store: TokenStore, oauth: OAuthClient, now: () -> Long = System::currentTimeMillis) {
  val status: StateFlow<AuthStatus>
  fun loginWithCode(code: String, verifier: String): Boolean
  fun completeLogin(tokens: TokenResponse): Boolean
  fun freshAccessToken(): String?
  fun forceRefresh(): String?
  fun hostToken(host: String): HostToken?
  fun putHostToken(host: String, t: HostToken)
  fun dropHostToken(host: String)
  fun signOut()
}
```

- [ ] **Step 1: Write the failing test** `core/src/test/java/io/rg2/radio/auth/AuthRepositoryTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run, expect FAIL** (unresolved `AuthRepository`)

Run: `cd ~/radio-android-auth && ./gradlew --no-daemon -q :core:testDebugUnitTest --tests 'io.rg2.radio.auth.AuthRepositoryTest' 2>&1 | grep -E "Unresolved" | head -2`
Expected: `Unresolved reference 'AuthRepository'` (or `InMemoryTokenStore`).

- [ ] **Step 3: Implement** `core/src/main/java/io/rg2/radio/auth/AuthState.kt`:

```kotlin
package io.rg2.radio.auth

import kotlinx.serialization.Serializable

@Serializable
data class HostToken(val token: String, val expiresAtMs: Long)

/** The persisted sign-in session (encrypted at rest by the platform [TokenStore]). */
@Serializable
data class AuthState(
    val refreshToken: String,
    val accessToken: String,
    val accessExpiresAtMs: Long,
    val username: String,
    val groups: List<String>,
    val hostTokens: Map<String, HostToken> = emptyMap(),
)

interface TokenStore {
    fun load(): AuthState?
    fun save(state: AuthState?)
}

class InMemoryTokenStore(var state: AuthState? = null) : TokenStore {
    override fun load() = state
    override fun save(state: AuthState?) { this.state = state }
}
```

Then `core/src/main/java/io/rg2/radio/auth/AuthRepository.kt`:

```kotlin
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
        val s = synchronized(this) { state }
        s?.let { oauth.revoke(it.refreshToken) }
        persist(null)
    }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `cd ~/radio-android-auth && ./gradlew --no-daemon -q :core:testDebugUnitTest --tests 'io.rg2.radio.auth.AuthRepositoryTest' && echo PASS`
Expected: `PASS`

- [ ] **Step 5: Commit**

```bash
cd ~/radio-android-auth && git add core/src && git commit -m "auth: AuthState/TokenStore + AuthRepository (rotation-safe refresh, revoke on sign-out)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Android — `HostTokenCache`

**Files:**
- Create: `core/src/main/java/io/rg2/radio/auth/HostTokenCache.kt`
- Test: `core/src/test/java/io/rg2/radio/auth/HostTokenCacheTest.kt`

**Interfaces:**
- Consumes: `AuthRepository`, `OAuthClient`, `AuthConfig`, `HostToken`.
- Produces: `interface HostTokenSource { fun tokenFor(host: String): String?; fun invalidate(host: String); fun isSignedIn(): Boolean }`, and `class HostTokenCache(config, repo, oauth, now) : HostTokenSource`.

- [ ] **Step 1: Write the failing test** `core/src/test/java/io/rg2/radio/auth/HostTokenCacheTest.kt`:

```kotlin
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

    private fun idToken() = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"preferred_username":"kid","groups":["family"]}""".toByteArray()) + ".s"

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
}
```

- [ ] **Step 2: Run, expect FAIL** (unresolved `HostTokenCache`)

Run: `cd ~/radio-android-auth && ./gradlew --no-daemon -q :core:testDebugUnitTest --tests 'io.rg2.radio.auth.HostTokenCacheTest' 2>&1 | grep -E "Unresolved" | head -2`
Expected: `Unresolved reference 'HostTokenCache'`.

- [ ] **Step 3: Implement** `core/src/main/java/io/rg2/radio/auth/HostTokenCache.kt`:

```kotlin
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
    private val noAccess = mutableSetOf<String>()

    override fun isSignedIn() = repo.status.value is AuthStatus.SignedIn

    @Synchronized
    override fun tokenFor(host: String): String? {
        val clientId = config.hostClientIds[host] ?: return null
        if (!isSignedIn() || host in noAccess) return null
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
            is OAuthResult.Err -> { if (result.error == "invalid_grant") noAccess += host; null }
            is OAuthResult.Network -> null
        }
    }

    @Synchronized
    override fun invalidate(host: String) = repo.dropHostToken(host)

    private companion object { const val MIN_LIFE_MS = 5 * 60_000L }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `cd ~/radio-android-auth && ./gradlew --no-daemon -q :core:testDebugUnitTest --tests 'io.rg2.radio.auth.HostTokenCacheTest' && echo PASS`
Expected: `PASS`

- [ ] **Step 5: Commit**

```bash
cd ~/radio-android-auth && git add core/src && git commit -m "auth: HostTokenCache — per-host exchange, refresh-on-invalid_grant, NoAccess, single-flight

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Android — `AuthInterceptor`, API clients (drop Basic, admin and sign-in errors)

**Files:**
- Create: `core/src/main/java/io/rg2/radio/auth/AuthInterceptor.kt`
- Modify: `core/src/main/java/io/rg2/radio/data/RadioSettings.kt`, `core/src/main/java/io/rg2/radio/data/RadioApi.kt`, `core/src/main/java/io/rg2/radio/data/ScannerApi.kt`
- Test: `core/src/test/java/io/rg2/radio/auth/AuthInterceptorTest.kt`

**Interfaces:**
- Consumes: `HostTokenSource` (Task 6).
- Produces:
  - `class AuthInterceptor(source: HostTokenSource, hostOf: (HttpUrl) -> String = { it.host }) : Interceptor`. `hostOf` lets the tests map MockWebServer to a host name.
  - `class SignInRequiredException : IOException("Sign in to use the radio away from home")`.
  - `class AdminRequiredException : RadioApiException(ADMIN_MESSAGE)`, with `const val ADMIN_MESSAGE = "Needs admin — your account can listen but not control"`.
  - `RadioApi.defaultClient(interceptor: Interceptor? = null)`.

- [ ] **Step 1: Write the failing test** `core/src/test/java/io/rg2/radio/auth/AuthInterceptorTest.kt`:

```kotlin
package io.rg2.radio.auth

import io.rg2.radio.data.ADMIN_MESSAGE
import io.rg2.radio.data.AdminRequiredException
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
            api.tune(TuneRequest(freq = 100.7, band = "fm"))
            fail("expected AdminRequiredException")
        } catch (e: AdminRequiredException) {
            assertEquals(ADMIN_MESSAGE, e.message)
        }
        assertTrue(server.requestCount >= 1)
    }
}
```

Note: check the `TuneRequest` constructor in `core/.../data/` before running. If its fields differ (e.g. `freq: Double, band: String`), adjust only the arguments in this test to a valid instance.

- [ ] **Step 2: Run, expect FAIL** (unresolved `AuthInterceptor`/`AdminRequiredException`)

Run: `cd ~/radio-android-auth && ./gradlew --no-daemon -q :core:testDebugUnitTest --tests 'io.rg2.radio.auth.AuthInterceptorTest' 2>&1 | grep -E "Unresolved" | head -3`
Expected: `Unresolved reference 'AuthInterceptor'` and/or `'AdminRequiredException'`.

- [ ] **Step 3: Implement** `core/src/main/java/io/rg2/radio/auth/AuthInterceptor.kt`:

```kotlin
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
```

In `core/src/main/java/io/rg2/radio/data/RadioSettings.kt`:
- Delete the `authHeader` property and its KDoc.
- Delete the `basicAuthHeader` function and the `import okhttp3.Credentials` line.
- In `InMemoryRadioSettings`, delete `override val authHeader: String? = null,`.
- Change the interface KDoc's first paragraph to: "Source of the user-configurable connection settings (backend base URLs). Credentials are handled by `io.rg2.radio.auth` (Authentik sign-in), not here."

In `core/src/main/java/io/rg2/radio/data/RadioApi.kt`:
- Add, after the `RadioApiException` class:

```kotlin
const val ADMIN_MESSAGE = "Needs admin — your account can listen but not control"

/** 403 `{"error":"admin required"}` from the platform write guard (off-LAN, not homelab-admin). */
class AdminRequiredException : RadioApiException(ADMIN_MESSAGE)
```

- Make `RadioApiException` `open`: `open class RadioApiException(...)`.
- In `postJson`, delete the line `s.authHeader?.let { builder.header("Authorization", it) }`. Inside `resp.use {`, before `if (it.isSuccessful || it.code == 400)`, insert:

```kotlin
            if (it.code == 403 && text.contains("admin required")) throw AdminRequiredException()
```

- Replace `fun defaultClient(): OkHttpClient = OkHttpClient.Builder()` with:

```kotlin
        fun defaultClient(interceptor: okhttp3.Interceptor? = null): OkHttpClient = OkHttpClient.Builder()
            .apply { interceptor?.let(::addInterceptor) }
```

- Update the class KDoc sentence about the write endpoint attaching an Authorization header to: "Authentication is added transparently by `io.rg2.radio.auth.AuthInterceptor` on the shared client."

In `core/src/main/java/io/rg2/radio/data/ScannerApi.kt`, delete both `s.authHeader?.let { builder.header("Authorization", it) }` lines, plus any `val s = settings()` lines left unused. In `post(...)`, before `if (it.isSuccessful || it.code == 400)`, insert:

```kotlin
            if (it.code == 403 && text.contains("admin required")) {
                return ScannerControlResponse(error = ADMIN_MESSAGE)
            }
```

In `setSquelch`, before `if (!it.isSuccessful)`, insert `if (it.code == 403) throw AdminRequiredException()`. Add the imports `io.rg2.radio.data.ADMIN_MESSAGE` if needed (same package, so none are required).

- [ ] **Step 4: Run the core suite and compile the consumers.** The app and wear modules may reference `authHeader`; fix any compile error by deleting those references.

Run: `cd ~/radio-android-auth && ./gradlew --no-daemon -q :core:testDebugUnitTest && ./gradlew --no-daemon -q :app:compileDebugKotlin :wear:compileDebugKotlin && echo PASS`
Expected: `PASS`

- [ ] **Step 5: Commit**

```bash
cd ~/radio-android-auth && git add core/src app/src wear/src && git commit -m "auth: AuthInterceptor (Bearer per host, one re-exchange, SignInRequired) + admin-required mapping; drop Basic authHeader

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Android — `DeviceCodeLogin`

**Files:**
- Create: `core/src/main/java/io/rg2/radio/auth/DeviceCodeLogin.kt`
- Test: `core/src/test/java/io/rg2/radio/auth/DeviceCodeLoginTest.kt`

**Interfaces:**
- Consumes: `OAuthClient.deviceAuthorize/pollDevice`, `AuthRepository.completeLogin`.
- Produces: `sealed interface DeviceLoginState { object Idle; data class ShowCode(userCode: String, verificationUri: String); object Success; data class Failed(message: String) }` and `class DeviceCodeLogin(oauth, repo) { val state: StateFlow<DeviceLoginState>; suspend fun run() }`.

- [ ] **Step 1: Write the failing test** `core/src/test/java/io/rg2/radio/auth/DeviceCodeLoginTest.kt`:

```kotlin
package io.rg2.radio.auth

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

    @Test fun slowDownStillCompletes() = runTest {
        server.enqueue(authorize); server.enqueue(pending("slow_down")); server.enqueue(tokens)
        login.run()
        assertEquals(DeviceLoginState.Success, login.state.value)
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
}
```

- [ ] **Step 2: Run, expect FAIL** (unresolved `DeviceCodeLogin`)

Run: `cd ~/radio-android-auth && ./gradlew --no-daemon -q :core:testDebugUnitTest --tests 'io.rg2.radio.auth.DeviceCodeLoginTest' 2>&1 | grep -E "Unresolved" | head -2`
Expected: `Unresolved reference 'DeviceCodeLogin'`.

- [ ] **Step 3: Implement** `core/src/main/java/io/rg2/radio/auth/DeviceCodeLogin.kt`:

```kotlin
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
```

- [ ] **Step 4: Run the whole core suite, expect PASS**

Run: `cd ~/radio-android-auth && ./gradlew --no-daemon -q :core:testDebugUnitTest && echo PASS`
Expected: `PASS`

- [ ] **Step 5: Commit**

```bash
cd ~/radio-android-auth && git add core/src && git commit -m "auth: DeviceCodeLogin (RFC 8628 polling) for Wear

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Android — `KeystoreTokenStore` + phone wiring

**Files:**
- Create: `core/src/main/java/io/rg2/radio/auth/KeystoreTokenStore.kt`, `app/src/main/java/io/rg2/radio/auth/AuthRedirectActivity.kt`, `app/src/main/java/io/rg2/radio/ui/AccountScreen.kt`
- Modify:
  - `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/io/rg2/radio/RadioApp.kt`, `app/src/main/java/io/rg2/radio/MainActivity.kt`;
  - `app/src/main/java/io/rg2/radio/playback/PlaybackService.kt`;
  - `app/src/main/java/io/rg2/radio/ui/NowPlayingViewModel.kt` and `NowPlayingScreen.kt`.

**Interfaces:**
- Consumes everything in `io.rg2.radio.auth`.
- Produces these `AppContainer` members:
  - `auth: AuthRepository`;
  - `hostTokens: HostTokenCache`;
  - `authHint: MutableStateFlow<String?>`;
  - `pendingLogin: SharedPreferences` keys `pkce_verifier` / `pkce_state`.

- [ ] **Step 1: `KeystoreTokenStore`** in `core/src/main/java/io/rg2/radio/auth/KeystoreTokenStore.kt`:

```kotlin
package io.rg2.radio.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [TokenStore] encrypted with a non-exportable Android Keystore AES-GCM key.
 * Any decrypt/parse failure (key wiped on device restore, etc.) reads as
 * signed-out and clears the blob — the user just signs in again.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("radio-auth", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    override fun load(): AuthState? = runCatching {
        val blob = Base64.decode(prefs.getString(KEY, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        json.decodeFromString(AuthState.serializer(), String(cipher.doFinal(blob, 12, blob.size - 12)))
    }.getOrElse { prefs.edit().remove(KEY).apply(); null }

    override fun save(state: AuthState?) {
        if (state == null) { prefs.edit().remove(KEY).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(json.encodeToString(AuthState.serializer(), state).toByteArray())
        prefs.edit().putString(KEY, Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)).apply()
    }

    private companion object { const val ALIAS = "radio-auth"; const val KEY = "state" }
}
```

- [ ] **Step 2: Container wiring.** In `app/src/main/java/io/rg2/radio/RadioApp.kt`:
- Change `container = AppContainer(getSharedPreferences("radio", Context.MODE_PRIVATE))` to `container = AppContainer(getSharedPreferences("radio", Context.MODE_PRIVATE), KeystoreTokenStore(this))`.
- Change the class header to `class AppContainer(private val prefs: SharedPreferences, tokenStore: TokenStore) {`.
- Replace `val httpClient: OkHttpClient = RadioApi.defaultClient()` with:

```kotlin
    val authConfig = io.rg2.radio.auth.AuthConfig.DEFAULT

    /** Plain client for Authentik itself (must NOT carry AuthInterceptor). */
    private val oauthHttp: OkHttpClient = RadioApi.defaultClient()
    val oauth = io.rg2.radio.auth.OAuthClient(authConfig, oauthHttp)
    val auth = io.rg2.radio.auth.AuthRepository(authConfig, tokenStore, oauth)
    val hostTokens = io.rg2.radio.auth.HostTokenCache(authConfig, auth, oauth)

    /** One OkHttp client (shared pool) for backends, artwork AND ExoPlayer streams. */
    val httpClient: OkHttpClient = RadioApi.defaultClient(io.rg2.radio.auth.AuthInterceptor(hostTokens))

    /** Non-null = show "sign in to listen away from home" (set by PlaybackService). */
    val authHint: MutableStateFlow<String?> = MutableStateFlow(null)
```

Add the imports `io.rg2.radio.auth.KeystoreTokenStore` and `io.rg2.radio.auth.TokenStore`.

- [ ] **Step 3: Redirect activity + manifest.** Create `app/src/main/java/io/rg2/radio/auth/AuthRedirectActivity.kt`:

```kotlin
package io.rg2.radio.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.rg2.radio.MainActivity
import io.rg2.radio.RadioApp
import kotlin.concurrent.thread

/** Receives io.rg2.radio:/oauth2redirect?code=…&state=… from the browser sign-in. */
class AuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RadioApp
        val data = intent?.data
        val pending = getSharedPreferences(PENDING_PREFS, MODE_PRIVATE)
        val verifier = pending.getString("pkce_verifier", null)
        val expected = pending.getString("pkce_state", null)
        pending.edit().clear().apply()
        val code = data?.getQueryParameter("code")
        if (code == null || verifier == null || data.getQueryParameter("state") != expected) {
            Toast.makeText(this, "Sign-in failed — try again", Toast.LENGTH_LONG).show()
            finishToMain(); return
        }
        thread {
            val ok = app.container.auth.loginWithCode(code, verifier)
            runOnUiThread {
                if (ok) app.container.authHint.value = null
                Toast.makeText(this, if (ok) "Signed in" else "Sign-in failed — try again", Toast.LENGTH_SHORT).show()
                finishToMain()
            }
        }
    }

    private fun finishToMain() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object { const val PENDING_PREFS = "radio-auth-pending" }
}
```

In `app/src/main/AndroidManifest.xml`, add this inside `<application>`, after the MainActivity `</activity>`:

```xml
        <!-- Authentik PKCE redirect: io.rg2.radio:/oauth2redirect -->
        <activity
            android:name=".auth.AuthRedirectActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:theme="@android:style/Theme.Translucent.NoTitleBar">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="io.rg2.radio" android:path="/oauth2redirect" />
            </intent-filter>
        </activity>
```

- [ ] **Step 4: Account tab.** Create `app/src/main/java/io/rg2/radio/ui/AccountScreen.kt`:

```kotlin
package io.rg2.radio.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.rg2.radio.RadioApp
import io.rg2.radio.auth.AuthRedirectActivity
import io.rg2.radio.auth.AuthStatus
import io.rg2.radio.auth.Pkce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AccountRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = (context.applicationContext as RadioApp).container
    val status by container.auth.status.collectAsState()
    val scope = rememberCoroutineScope()
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("ACCOUNT", style = MaterialTheme.typography.titleMedium)
        Text(
            when (val s = status) {
                AuthStatus.SignedOut -> "Not signed in — works at home; sign in to listen away from home."
                AuthStatus.Expired -> "Session expired — sign in again."
                is AuthStatus.SignedIn -> "Signed in as ${s.username} (${if (s.isAdmin) "admin" else "family"})"
            },
        )
        if (status is AuthStatus.SignedIn) {
            OutlinedButton(onClick = {
                scope.launch { withContext(Dispatchers.IO) { container.auth.signOut() } }
            }) { Text("Sign out") }
        } else {
            Button(onClick = { startSignIn(context, container.authConfig) }) { Text("Sign in") }
        }
    }
}

private fun startSignIn(context: Context, config: io.rg2.radio.auth.AuthConfig) {
    val verifier = Pkce.newVerifier()
    val state = Pkce.newState()
    context.getSharedPreferences(AuthRedirectActivity.PENDING_PREFS, Context.MODE_PRIVATE).edit()
        .putString("pkce_verifier", verifier).putString("pkce_state", state).commit()
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(Pkce.authorizeUrl(config, Pkce.challenge(verifier), state)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
```

In `app/src/main/java/io/rg2/radio/MainActivity.kt`:
- Change `private enum class Tab(val label: String) { RADIO("RADIO"), SCANNER("SCANNER") }` to `private enum class Tab(val label: String) { RADIO("RADIO"), SCANNER("SCANNER"), ACCOUNT("ACCOUNT") }`.
- In the `when (tab)`, add `Tab.ACCOUNT -> AccountRoute(Modifier.padding(padding))`.
- Add `import io.rg2.radio.ui.AccountRoute`.

- [ ] **Step 5: ExoPlayer over the authenticated client, plus the sign-in hint.** In `app/build.gradle.kts` `dependencies`, after `implementation(libs.media3.session)`, add `implementation(libs.media3.datasource.okhttp)`. In `PlaybackService.onCreate`, insert this into the `ExoPlayer.Builder(...)` chain before `.setAudioAttributes(`:

```kotlin
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(container.httpClient),
                ),
            )
```

In `ReconnectListener.onPlayerError`, insert this as the first statements:

```kotlin
            if (generateSequence(error as Throwable?) { it.cause }.any { it is io.rg2.radio.auth.SignInRequiredException }) {
                container.authHint.value = "Sign in (ACCOUNT tab) to listen away from home"
                Log.i(TAG, "stream needs sign-in; not reconnecting")
                return
            }
```

- [ ] **Step 6: Android Auto hint.** In `onGetChildren`, replace the `Favorites.ROOT_ID -> {` branch's first line, `val items = Favorites.SEED.map(::favoriteBrowseItem) + stationsFolderItem()`, with:

```kotlin
                val hint = container.authHint.value?.let { listOf(signInHintItem()) } ?: emptyList()
                val items = hint + Favorites.SEED.map(::favoriteBrowseItem) + stationsFolderItem()
```

Then add next to `rootItem()`:

```kotlin
    private fun signInHintItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId("auth:sign-in-hint")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle("Sign in on your phone")
                    .setSubtitle("Needed to listen away from home")
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .build(),
            )
            .build()
```

- [ ] **Step 7: Admin message and auth banner in Now Playing.** In `NowPlayingViewModel.kt`:
- Add `private val _actionMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)`, `val actionMessage: kotlinx.coroutines.flow.StateFlow<String?> = _actionMessage` and `fun clearActionMessage() { _actionMessage.value = null }`.
- In `postSetting`, change `.onFailure { Log.w(TAG, "$what failed", it) }` to:

```kotlin
                .onFailure {
                    Log.w(TAG, "$what failed", it)
                    if (it is io.rg2.radio.data.AdminRequiredException ||
                        it is io.rg2.radio.auth.SignInRequiredException) _actionMessage.value = it.message
                }
```

- Apply the same `onFailure` change to the tune call's failure handler. Locate it with `grep -n "api.tune" app/src/main/java/io/rg2/radio/ui/NowPlayingViewModel.kt`.

In `NowPlayingScreen.kt`, inside `NowPlayingRoute` (where the ViewModel is obtained), add:

```kotlin
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val actionMsg by vm.actionMessage.collectAsState()
    LaunchedEffect(actionMsg) {
        actionMsg?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show(); vm.clearActionMessage() }
    }
    val authHint by (ctx.applicationContext as io.rg2.radio.RadioApp).container.authHint.collectAsState()
```

Then render `authHint`, when non-null, as a `Text` in `MaterialTheme.colorScheme.error` above the screen content. Wrap the existing screen call in a `Column` whose first child is `authHint?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }`. Add whatever Compose imports are missing (`LaunchedEffect`, `collectAsState`, `getValue`, `Column`, `padding`, `dp`).

- [ ] **Step 8: Build + full core suite**

Run: `cd ~/radio-android-auth && ./gradlew --no-daemon -q :core:testDebugUnitTest :app:assembleDebug && ls -la app/build/outputs/apk/debug/*.apk`
Expected: tests pass and the debug APK is listed.

- [ ] **Step 9: Commit**

```bash
cd ~/radio-android-auth && git add core/src app/ && git commit -m "app: Authentik sign-in — keystore token store, PKCE redirect activity, ACCOUNT tab, authenticated ExoPlayer, admin/sign-in messages, Auto hint

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Android — Wear wiring (device-code sign-in)

**Files:**
- Modify: `wear/build.gradle.kts`, `wear/src/main/java/io/rg2/radio/wear/WearApp.kt`, `wear/src/main/java/io/rg2/radio/wear/playback/WearPlaybackService.kt`, `wear/src/main/java/io/rg2/radio/wear/ui/WearHomeScreen.kt`
- Create: `wear/src/main/java/io/rg2/radio/wear/ui/WearAccountScreen.kt`

**Interfaces:**
- Consumes `io.rg2.radio.auth.*` (Tasks 3–9).
- Produces `WearContainer.auth`, `.hostTokens`, `.oauth` and `.authConfig`.

- [ ] **Step 1: Container.** In `WearApp.kt`:
- Change `container = WearContainer()` to `container = WearContainer(io.rg2.radio.auth.KeystoreTokenStore(this))`.
- Change the class header to `class WearContainer(tokenStore: io.rg2.radio.auth.TokenStore) {`.
- Replace `val httpClient: OkHttpClient = RadioApi.defaultClient()` with:

```kotlin
    val authConfig = io.rg2.radio.auth.AuthConfig.DEFAULT
    val oauth = io.rg2.radio.auth.OAuthClient(authConfig, RadioApi.defaultClient())
    val auth = io.rg2.radio.auth.AuthRepository(authConfig, tokenStore, oauth)
    val hostTokens = io.rg2.radio.auth.HostTokenCache(authConfig, auth, oauth)
    val httpClient: OkHttpClient = RadioApi.defaultClient(io.rg2.radio.auth.AuthInterceptor(hostTokens))
```

- [ ] **Step 2: ExoPlayer.** In `wear/build.gradle.kts` `dependencies`, add `implementation(libs.media3.datasource.okhttp)` next to the other media3 lines. In `WearPlaybackService.onCreate`, change `player = ExoPlayer.Builder(this)` to:

```kotlin
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(container.httpClient),
                ),
            )
```

- [ ] **Step 3: Account screen.** Create `wear/src/main/java/io/rg2/radio/wear/ui/WearAccountScreen.kt`:

```kotlin
package io.rg2.radio.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import io.rg2.radio.auth.AuthStatus
import io.rg2.radio.auth.DeviceCodeLogin
import io.rg2.radio.auth.DeviceLoginState
import io.rg2.radio.wear.WearApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WearAccountScreen(onDone: () -> Unit) {
    val container = (LocalContext.current.applicationContext as WearApp).container
    val status by container.auth.status.collectAsState()
    val login = remember { DeviceCodeLogin(container.oauth, container.auth) }
    val dl by login.state.collectAsState()
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            dl is DeviceLoginState.ShowCode -> {
                val s = dl as DeviceLoginState.ShowCode
                Text("Go to", textAlign = TextAlign.Center)
                Text("authentik.bobgardner.org/device", textAlign = TextAlign.Center)
                Text(s.userCode, textAlign = TextAlign.Center)
            }
            status is AuthStatus.SignedIn -> {
                Text("Signed in as ${(status as AuthStatus.SignedIn).username}", textAlign = TextAlign.Center)
                Chip(label = { Text("Sign out") }, onClick = {
                    scope.launch { withContext(Dispatchers.IO) { container.auth.signOut() } }
                })
                Chip(label = { Text("Back") }, onClick = onDone)
            }
            else -> {
                (dl as? DeviceLoginState.Failed)?.let { Text(it.message, textAlign = TextAlign.Center) }
                Chip(label = { Text("Sign in") }, onClick = {
                    scope.launch { withContext(Dispatchers.IO) { login.run() } }
                })
                Chip(label = { Text("Back") }, onClick = onDone)
            }
        }
    }
}
```

- [ ] **Step 4: Entry point.** In `WearHomeScreen.kt`, find the top-level `@Composable fun RadioWearApp(` and wrap its body:

```kotlin
    var showAccount by remember { mutableStateOf(false) }
    if (showAccount) { WearAccountScreen(onDone = { showAccount = false }); return }
```

These go as the first statements of `RadioWearApp`. Then, in the main `ScalingLazyColumn`, add a last item:

```kotlin
            item {
                SourceChip(
                    label = "Account",
                    secondary = "Sign in for away-from-home",
                    active = false,
                    enabled = true,
                    onClick = { showAccount = true },
                )
            }
```

If `RadioWearApp` delegates the list to an inner composable, thread an `onAccount: () -> Unit` parameter through to it instead of capturing `showAccount`. Add any missing imports (`mutableStateOf`, `setValue`).

- [ ] **Step 5: Build**

Run: `cd ~/radio-android-auth && ./gradlew --no-daemon -q :core:testDebugUnitTest :wear:assembleDebug :app:assembleDebug && ls wear/build/outputs/apk/debug/*.apk app/build/outputs/apk/debug/*.apk`
Expected: both APKs are listed.

- [ ] **Step 6: Commit**

```bash
cd ~/radio-android-auth && git add wear/ && git commit -m "wear: device-code Authentik sign-in, authenticated client + ExoPlayer

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Device verification (user), docs, PR, then the batch-B flip

- [ ] **Step 1: Hand off the APKs.**
  - Copy `app/build/outputs/apk/debug/app-debug.apk` and `wear/build/outputs/apk/debug/wear-debug.apk` into the session scratchpad and give the user the paths. Don't put them in the repo.
  - The repo's CLAUDE.md warns that an old build signed with a different debug key blocks the update, so the user should uninstall first if the version label doesn't change.
- [ ] **Step 2: User checks on the phone, then a server-side check.**
  - **LAN, signed out:** radio plays and tune works.
  - **ACCOUNT → Sign in:** the browser opens Authentik, you log in, and the app shows "Signed in as <user> (admin|family)".
  - After a minute of normal use while signed in, prove the per-host exchange happened (radio, ems and icecast aren't gated yet, so off-LAN behaviour can't show it). From codeserver:

```bash
set -a; . ~/.config/radio-auth.env; set +a; H="Authorization: Bearer $AUTHENTIK_TOKEN"; A="$AUTHENTIK_URL/api/v3"
ME=$(curl -sk -H "$H" $A/core/users/me/ | python3 -c 'import sys,json;print(json.load(sys.stdin)["user"]["pk"])')
for pv in 90 91 93; do echo -n "provider $pv tokens: "; curl -sk -H "$H" "$A/oauth2/access_tokens/?provider=$pv&user=$ME" | python3 -c "import sys,json;print(json.load(sys.stdin)['pagination']['count'])"; done
```

  - **Expected:** a count of at least 1 for provider 90 (radio) and 93 (icecast, once a stream played), and 91 (ems) if the Scanner tab was opened.
  - **Sign out:** the status returns to "Not signed in", and the refresh token is revoked: `oauth2/refresh_tokens/?provider=94&user=$ME` returns a count of 0.
- [ ] **Step 3: User checks on the watch.** Account → Sign in shows a code. Approve it at `authentik.bobgardner.org/device` on the phone; the watch shows "Signed in as …". It plays radio on the watch's own network.
- [ ] **Step 4: Docs + PR.**
  - In radio-android `CLAUDE.md`, replace the "Auth and transport constraints" bullets about NPMplus basic auth with a short paragraph: Authentik sign-in (PKCE on the phone, device code on Wear); a per-host token exchange via `io.rg2.radio.auth`; a pointer to the spec.
  - Remove "Not started: the encrypted settings/credentials store" from current state.
  - Commit, push `feat/authentik-signin`, then run `gh pr create --base main` with a summary and the 🤖 footer. Don't merge.
- [ ] **Step 5: Batch-B flip** (after the user confirms Steps 2–3). From `~/projects/platform-radio-auth`:

```bash
set -a; . ~/.config/npm-proxy.env; . ~/.config/radio-auth.env; set +a
for h in wx.rg2.io icecast.rg2.io ems.rg2.io radio.rg2.io; do python3 tools/radio-auth.py apply --npm-only --only $h || break; done
python3 tools/radio-auth.py plan | grep -vE "^[a-z0-9.]+\.rg2\.io$"
```

Expected: every host `= converged`. Then the user runs an LTE pass:
  - the phone app plays `/fm.mp3`;
  - an admin tune works;
  - the watch plays;
  - weather.bobgardner.org shows the alert banner, NOAA audio and the GOES image.

Update the platform `CLAUDE.md` access-control bullet to "12 of 12 gated" and commit to PR #46.
