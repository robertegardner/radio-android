# Authentik sign-in for the phone + Wear apps (design)

Date: 2026-10-01 · Branch: `feat/authentik-signin` (off `main`) · Status: approved design, pre-plan
Companion: platform spec `docs/superpowers/specs/2026-10-01-radio-authentik-access-design.md`
(platform PR #46). This spec unblocks that spec's "batch B" flip.

## Intent

The platform now gates the radio `*.rg2.io` hosts behind Authentik off-LAN. `family`
can listen and view, and `homelab-admin` can control. Four hosts are still open:
radio, ems, wx and icecast. They are held open only because the app can't
authenticate yet, and its main use case is listening to Cardinals games **in the
car, off-LAN**.

Goal: each family member signs in once with their own Authentik account, on their
phone and their Wear watch. After that the app keeps working off-LAN after the flip,
for both API calls and audio streams, with no admin-minted secrets.

Success:
- On the phone with Wi-Fi off, signed in, `/fm.mp3` plays.
- An admin can tune. A family member gets a clear "needs admin" message.
- The watch signs in by device code and plays standalone.
- At home everything works without signing in, as it does today.

## Decisions (user-confirmed)

1. Clients in scope: the **phone app (including Android Auto) and Wear**. Garmin
   stays LAN-only, as a later follow-up.
2. Users: **multiple family members**, each with their own login.
3. Mechanism: **in-app Authentik login (OAuth2 PKCE) plus a per-host token
   exchange**. Not app passwords.
4. Watch: **its own device-code login**, not tokens synced from the phone.
5. Exchanged-token lifetime of **24 h** is accepted (it's the revocation lag). The
   host map is **baked into the build**.

## Spike results (2026-10-01, all verified live)

- **No direct acceptance.** The proxy outpost accepts a `Bearer` JWT only if that
  host's own proxy provider issued it. Tokens from other clients are not accepted
  directly; Authentik's docs say the same.
- **Federation is a token exchange.** The app POSTs to
  `https://authentik.bobgardner.org/application/o/token/` with
  `grant_type=client_credentials`,
  `client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer`,
  `client_assertion=<radio-android access token>`, `client_id=<host provider client_id>`
  and `scope=openid profile email ak_proxy`. The response is a host JWT valid for
  86400 s.
- **Scopes:** without `profile email ak_proxy`, the backend receives empty
  `X-authentik-username/groups`, so even admin writes fail.
- **The exchange enforces the host application's group bindings.** A user in no
  group gets `invalid_grant`. A host whose provider doesn't federate `radio-android`
  also returns `invalid_grant` (verified at goes).
- **The full chain was proven.** Browser PKCE login, then code redeemed (1 h access
  token plus refresh token), then exchange at rf, then rf with `X-Forwarded-For`
  simulating off-LAN: the backend saw `rgardner` with `admin: true` and the POST
  passed the guard. Refresh works and **rotates** the refresh token.
- **Gotchas:**
  - OAuth2 providers created through the API start with `grant_types: []`. Every
    grant then fails with `invalid_grant`, or with an "Unsafe redirect" crash when
    a custom scheme is used.
  - Provider PATCH re-validates `mode`, so it must be sent.
  - The JWT `iss` follows the request Host. The app always uses
    `authentik.bobgardner.org`.
  - A browser with no app installed ignores the `io.rg2.radio:` redirect, so the
    page looks stuck on "loading". On a phone with the app installed, Android routes
    it to the app.

## Authentik / platform state

- **OAuth2 provider `radio-android`** (pk 94, application slug `radio-android`, group
  "Radio Platform", bound to `family` and `homelab-admin`):
  - public client, PKCE, `client_id` `hemTnLT7eGgimzjqizkXFTGlWI0phGmjPaLaWm0O`;
  - redirect `io.rg2.radio:/oauth2redirect` (strict);
  - grant types `authorization_code`, `refresh_token`,
    `urn:ietf:params:oauth:grant-type:device_code`;
  - scopes openid, email, profile, offline_access;
  - access token 1 h, refresh token 30 d with rotation, code validity 1 min;
  - signing key `a26bfb5d…` (the same one the proxy providers use).
  - **It exists now.** It was created during the spike and is not yet in the
    registry.
- **Platform changes (the platform repo, on the open PR #46 branch):**
  1. `terraform/registry/auth.json` gains `"federate": ["radio-android"]`.
     `radio-auth.py` resolves that name to the OAuth2 provider pk and converges
     `jwt_federation_providers` on every registry host's proxy provider. This uses a
     pure core function with tests, and only *adds* the managed pk, keeping foreign
     pks.
  2. Fix: provider PATCHes in `radio-auth.py` always include `"mode": "forward_single"`.
  3. `radio-auth.py` also ensures `radio-android`'s `grant_types` and its bindings,
     so a re-created client isn't silently broken.
  4. **One-time manual step:** the device-code flow on the default brand.
     - Create a flow (slug `default-device-code`, designation `stage_configuration`).
     - Set `brand.flow_device_code` to it. Today it is unset, so `/device` and
       device-code polling don't work.
     - Record it in the platform runbook. `radio-auth.py` does not manage brands.
- **Host map** (proxy provider client_ids). The registry is the source; these are
  copied into the app build config:

  | Host | provider pk | client_id |
  |---|---|---|
  | radio.rg2.io | 90 | `EejQH8q8sJLXZIdRiCkeq9L5Ytn6xTzB0x84f9jU` |
  | ems.rg2.io | 91 | `qMx0qI2CruW3bSQK8aC4T2ytq2xSmX0jaP5wyW5p` |
  | icecast.rg2.io | 93 | `0udNEfQq1J7cKJO5JequfHA4YEPDWGe6wZIRFXlK` |

## Architecture (app)

All new auth code lives in **`core`**, shared by `app` and `wear`.

### `AuthConfig` (data)
Holds the issuer (`https://authentik.bobgardner.org/application/o/radio-android/`),
the `radio-android` `client_id`, the redirect URI, the token endpoint
`https://authentik.bobgardner.org/application/o/token/`, the device endpoint
`https://authentik.bobgardner.org/application/o/device/`, the login scopes
`openid profile email offline_access`, the exchange scopes
`openid profile email ak_proxy`, and the `hostClientIds: Map<String, String>` from
the table above. Values come from `BuildConfig` fields so a debug build can
override them.

### `TokenStore`
Encrypted persistence: Jetpack DataStore with a Tink AEAD whose key lives in
Android Keystore. It holds the login refresh token, the login access token plus
its expiry, the ID-token claims (username, groups) for display, and per-host
exchanged tokens plus their expiry. It exposes `clear()`. The interface lives in
`core`; the Keystore-backed implementation is Android-only. Tests use an
in-memory fake.

### `AuthRepository`
The sign-in session as a state machine: `SignedOut | SignedIn(user, groups) | Expired`.
- `freshLoginAccessToken()` returns the cached token, or refreshes it, persisting
  the **rotated** refresh token.
- On a refresh `invalid_grant`, it moves to `Expired`.
- `signOut()` POSTs the refresh token to the revoke endpoint
  (`/application/o/revoke/`), then clears the store.
- Phone login: AppAuth-Android `AuthorizationService` with a Custom Tab and PKCE.
  `RedirectUriReceiverActivity` registers the scheme `io.rg2.radio` in `app`'s
  manifest.
- Watch login: `DeviceCodeLogin` (below).

### `HostTokenCache`
`suspend fun tokenFor(host): String?` returns:
- null if the host isn't in the map, or if signed out;
- the cached token if more than 5 minutes of life are left;
- otherwise the result of an exchange using `freshLoginAccessToken()`.

On an exchange `invalid_grant`, it refreshes the login once and retries. If that
still fails, it marks the host `NoAccess` for the session and returns null.
`invalidate(host)` drops the cached token.

### `AuthInterceptor`
An OkHttp interceptor on the **one shared client** (`RadioApi.defaultClient()`
gains it).
- For a request to a mapped host, it adds `Authorization: Bearer <tokenFor(host)>`
  when one is available.
- If the response is a 302 whose `Location` contains `/outpost.goauthentik.io/`,
  or a 401, it calls `invalidate(host)` and retries **once** with a fresh token.
- It never loops.
- It never touches unmapped hosts, and sends no header when signed out. On LAN the
  request still succeeds, because NPM's LAN bypass doesn't require auth.
- The existing `RadioSettings.authHeader` Basic path is **removed**. It was never
  wired to UI, and the interceptor replaces it.

### ExoPlayer
`app` `LiveStreamPlayer`/`PlaybackService` and `wear` `WearPlaybackService` build
their media source with `OkHttpDataSource.Factory(sharedClient)`, adding the
dependency `androidx.media3:media3-datasource-okhttp` at the existing media3
version. Streams on icecast therefore carry the Bearer. Each stream is checked
once, at connect.

### `DeviceCodeLogin` (Wear)
- POST to the device endpoint, which returns `device_code`, `user_code`,
  `verification_uri(_complete)` and `interval`.
- Poll the token endpoint with `grant_type=urn:ietf:params:oauth:grant-type:device_code`.
- Handle each response:
  - `authorization_pending`: keep polling;
  - `slow_down`: add 5 s to the interval;
  - `expired_token`: "Code expired, try again";
  - `access_denied`: "Not allowed".
- On success, store the tokens like the phone does. The watch has **its own**
  refresh token; rotation makes sharing with the phone unsafe.

## UX

**Phone:**
- A gear icon on the Now Playing header opens a **settings sheet**. Its Account
  row shows "Not signed in" or "Signed in as <user> (family | admin)". The role
  comes from the ID-token `groups` and is display only.
- The sheet has **Sign in** (Custom Tab) and **Sign out** (revoke and clear).
- A write that returns 403 `{"error":"admin required"}` shows the toast
  "Needs admin — you're signed in as family". Controls are not hidden.
- In the `Expired` state, a "Sign in again" banner appears on the next
  auth-dependent failure. A stream that's already playing continues.
- If ExoPlayer gets a 302 or HTML instead of audio while signed out, the player
  shows "Sign in to listen away from home" instead of a generic error.
- Android Auto: when signed out and a stream start fails for that reason, the browse
  tree shows a non-playable "Sign in on your phone" item.

**Watch:**
- Account screen: Sign in shows the `user_code`, `authentik.bobgardner.org/device`,
  and a QR code of `verification_uri_complete`, then polls.
- After sign-in it shows "Signed in as <user>", plus Sign out.

## Error handling

| Situation | Behavior |
|---|---|
| Exchange `invalid_grant` | Refresh the login once, then re-exchange. If the refresh fails: `Expired`, and the banner. |
| User not allowed on a host (exchange refused even with a fresh login) | That host is `NoAccess` for the session; no retry loop |
| Authentik unreachable, on LAN | Requests succeed without a header (LAN bypass) |
| Authentik unreachable, off-LAN | Cached host tokens keep working until expiry; after that, normal network error |
| Off-LAN 302 to login on an API call | Interceptor re-exchanges once. Still 302: surfaced as the "sign in" state. |
| Device-code polling responses | As listed under `DeviceCodeLogin` |
| 403 `admin required` | "Needs admin" toast (the app does not retry) |

## Testing

The repo has **no tests today**. Add JVM unit-test infrastructure to `core`:
JUnit 4, `com.squareup.okhttp3:mockwebserver` at the OkHttp version already in
use, and `kotlinx-coroutines-test`. Use pure fakes for `TokenStore`. Add no
Robolectric.

- **`HostTokenCache`:**
  - unmapped host returns null;
  - signed out returns null;
  - a cache hit makes no network call;
  - near expiry triggers a re-exchange;
  - `invalid_grant` leads to refresh, then retry, then success;
  - `invalid_grant` twice leads to `NoAccess` and no further exchange calls;
  - the exchange request has the exact form fields and scope.
- **`AuthInterceptor`** (MockWebServer):
  - adds the header only for mapped hosts;
  - a 302 to the outpost leads to one retry with a new token;
  - a second 302 is returned as-is, so it never loops;
  - a 401 leads to the same retry;
  - signed out sends no header.
- **`AuthRepository`:**
  - refresh rotation is persisted;
  - a refresh `invalid_grant` moves to `Expired`;
  - `signOut` calls revoke, then clears.
- **`DeviceCodeLogin`:** pending, then success; `slow_down` raises the interval;
  `expired_token`; `access_denied`.
- **Platform** (in the platform repo):
  - `radio_auth_core` tests for the federation union (add only, keeps foreign pks,
    converged means no PATCH);
  - the PATCH payload always includes `mode`.
- **On the device:**
  - Pixel phone sign-in round trip;
  - Wi-Fi off: `/fm.mp3` plays, an admin tune works, a family account tune shows
    "Needs admin";
  - the watch signs in by device code and plays standalone on LTE/Wi-Fi away from
    home;
  - sign-out revokes.

## Rollout

1. **Platform:**
   - registry `federate` + `radio-auth.py` (federation convergence, `mode` fix,
     `radio-android` grant types and bindings);
   - `apply`, which federates `radio-android` on all 12 host providers;
   - brand device-code flow (manual);
   - tests.
   This is safe before the app ships: federation only enables an extra exchange path.
2. **App:** `feat/authentik-signin`, then sideload to the Pixel and the Wear watch.
3. **Device verification** as listed under Testing, with LAN checks done before the
   flip.
4. **Batch B flip** (radio, ems, wx, icecast) via the platform runbook. Then an LTE
   pass on the phone app and on the weather.bobgardner.org embeds.

## Out of scope

- Garmin. It stays LAN-only until it compiles; an app password is the likely
  future path.
- Hiding admin-only controls by role.
- Lowering the 24 h host-token validity.
- An in-app account switcher (sign out, then sign in, is enough).

## Plan-time refinements (2026-10-01, from the implementation plan)

1. PKCE is hand-rolled (OkHttp + a browser `ACTION_VIEW` intent; redirect caught by
   `AuthRedirectActivity`) instead of AppAuth/Custom Tabs — no new dependency and the
   login logic is JVM-testable.
2. Tokens are stored with an Android Keystore AES-GCM key over SharedPreferences
   (`KeystoreTokenStore`) instead of DataStore + Tink — no new dependency.
3. Phone Account UI is an ACCOUNT tab in the existing bottom tab bar, not a gear + sheet.
4. The watch device-code screen shows the user code + URL as text; no QR code.
