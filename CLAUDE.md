# CLAUDE.md

Project memory for [Claude Code](https://code.claude.com). Read this first when
starting a new session.

## What this project is

A native **Android client** for the SDR FM/AM radio backend that runs on the
attic Raspberry Pi and is exposed at **`https://radio.rg2.io`**. The app tunes
stations, plays the live Icecast stream, and shows now-playing metadata —
remotely, from a phone or a Chromebook, over the internet.

This repo is **client-only** by default: it owns no hardware, runs no SDR, and
contains no Python. It talks to the existing backend over HTTP(S). The backend
lives in a separate repo (`github.com/robertegardner/radio`). Don't touch the
backend from a normal session — **except** when Bob explicitly asks for
coordinated cross-repo work (e.g. adding an API field the app needs). For that,
the backend is cloned at `~/projects/radio` and the Pi is reachable at
`ssh radio.srvr`; deploy with surgical service restarts and **test on the live
box before committing** (backend convention). See the `backend-access` memory.

**Primary use case (drives every priority decision): St. Louis Cardinals
baseball**, a lot of it listened to in the car. That single fact is why this is
a native app and not a web wrapper — see below.

## Current state (2026-06-03)

The greenfield scaffold is long done; the app is substantially built and
device-verified. Implemented:

- **Playback** (Media3): background audio, lock-screen/notification transport,
  live-edge pause/resume, reconnect-on-error. Android Auto browse tree exists
  but is **untested on Auto** (needs the Desktop Head Unit).
- **Now-playing**: ViewModel + StateFlow polling; live metadata pumped into the
  media session so the notification tracks what's on air.
- **UI** (amber-LCD `ui/theme`): tuner panel with **album art** behind it, a
  **Track ID tile** (artist/title/album + a source badge RDS/CHROMAPRINT/LYRIC
  MATCH + a raw RDS readout), a captions toggle, and audio **visualizers**
  (BARS/PEAKS/MIRROR/SCOPE/FRACTAL/SYNTH; reactive ones use the real FFT via
  `RECORD_AUDIO`).
- **Build/version**: `v<ver> · <git sha>` (with `-dirty`) shown under the header
  via `BuildConfig.GIT_SHA` (config-cache-safe `providers.exec` in
  `app/build.gradle.kts`) — use it to confirm which build is on a test device.
- **Networking**: OkHttp + kotlinx.serialization, app-level `AppContainer` in
  `RadioApp` holding the shared client/settings/repositories.

**Not started:** the encrypted settings/credentials store + settings screen
(currently `InMemoryRadioSettings`, default base URL, no auth).

Backend song identification (RDS → AcoustID → lyric match) and its known
song-boundary limitation are in the `song-id-pipeline` memory.

## Why native (not a PWA)

The Cardinals network is largely **AM** (KMOX is the flagship) and a lot of
listening happens while driving. **Android Auto** and rock-solid background
playback with lock-screen controls are the features that matter, and a PWA
can't deliver Android Auto. So: native, with Media3 driving a real media
session.

If this is ever reconsidered for a PWA (no SDK, builds with plain web tooling,
installs from the existing `/radio` page), the backend-contract and auth
sections below still apply unchanged — only the client tech swaps.

## Target architecture

- **Language/UI:** Kotlin + Jetpack Compose, single-activity.
- **Playback:** Media3 (ExoPlayer) for the Icecast MP3 stream. A
  `MediaSessionService` for notification + lock-screen transport controls, and
  a `MediaLibraryService` exposing a browse tree for **Android Auto** (favorites
  → tune). Background playback runs in a foreground service.
- **Networking:** keep deps minimal (house style — the Pi projects are
  stdlib-only by preference). OkHttp + kotlinx.serialization is enough; reach
  for Retrofit only if it actually earns its weight.
- **State:** ViewModel + StateFlow/Coroutines. Poll `/api/now_playing` ~every
  1s and `/api/stations` ~every 30s, mirroring what the web `/radio` UI does.
- **SDK levels:** `compileSdk`/`targetSdk` 36 (Android 16), `minSdk` 26.
  JDK 21 (Debian 13 default — see the VM notes). Gradle via the wrapper; never
  install a global Gradle.
- **Package name:** `io.rg2.radio` (matches the rg2.io domain) unless you prefer
  otherwise.

### Foreground-service / permission gotchas (targetSdk 34+)

Don't get bitten by modern FGS rules:
- Declare the foreground service with `android:foregroundServiceType="mediaPlayback"`
  and request `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
- `POST_NOTIFICATIONS` is a runtime permission on API 33+ (needed for the media
  notification).
- Plus the obvious `INTERNET` and `ACCESS_NETWORK_STATE`.

## The backend API — the live server is the source of truth

Base URL: `https://radio.rg2.io`  ·  Stream: `https://icecast.rg2.io/fm.mp3`

> **The verified schemas live in `docs/api.md`** — modeled from real responses
> and kept current (it documents the discrete `now_playing.track` block:
> `{artist,title,album,art_url,source}` with server-fetched cover art, which the
> app prefers over its iTunes fallback). The tune body is `{freq, band, hd,
> subchannel}` (key is `band` = `"fm"`/`"am"`, **not** `mode`/`wbfm`). Re-curl
> the live endpoints if anything looks off; the read endpoints are public.

| Endpoint | Method | Use | Notes |
|---|---|---|---|
| `/api/now_playing` | GET | poll ~1s | RDS + caption/lyric state + the discrete `track` block (artist/title/album/art_url/source). The single feed for the now-playing UI. |
| `/api/stations` | GET | poll ~30s | Scanned FM + AM station list. Drives the station browser. |
| `/api/tune` | POST | on user action | JSON `{freq, band, hd, subchannel}`; writes `active.env` and restarts the stream. Returns `{ok, error}`. |
| `/api/status` | GET | confirm tune | Lightweight current band/freq/status. |
| `/radio` | — | reference | The existing web tuner UI. |
| `/` (admin) | — | n/a | Admin UI; not something the app should drive. |

## Auth and transport constraints

- **HTTPS only. Do NOT add a cleartext-traffic exception.** Android blocks
  cleartext by default and both hosts are already TLS behind NPMplus. Keep it
  that way.
- **Read endpoints are public-safe; writes are not.** `/radio`,
  `/api/now_playing`, and `/api/stations` are open. `/api/tune` (and the admin
  routes) are intended to sit behind **NPMplus basic auth** — that auth may not
  be live yet, but build as if it will be:
  - Attach an `Authorization` header to write requests.
  - Store credentials with EncryptedSharedPreferences or DataStore — never in
    the repo, never hardcoded.
  - Make the **base URL and credentials user-configurable** in a settings
    screen, so the app works before and after auth lands and against a LAN IP
    during testing.

## Stations / presets — Cardinals first

Seed these as default favorites (confirm call signs/freqs against
`/api/stations`):

- **KMOX 1120 AM** — St. Louis, Cardinals flagship (50 kW clear-channel).
  Verified working on the backend.
- **KZYM 1230 AM** — Cape Girardeau Cardinals affiliate.
- **KGMO 100.7 FM** — primary local FM, verified working.
- **95.7 FM** — FM affiliate that also carries games.

Because the Cardinals network is AM-centric, **AM tune reliability and audio
clarity matter more than premium FM** for the core goal.

## Things the backend does NOT do — don't build these

- **No HD Radio.** The SDR (SDRplay RSPdx-R2) can't do HD with the current
  software, so HD is dead. `now_playing` may still expose `hd_*` flags, but
  they'll read unavailable. Handle the fields gracefully and **build no HD
  subchannel UI / selector.**
- **FM is mono, ~128k.** No stereo today (backburnered upstream). Nothing for
  the app to do here beyond not assuming stereo metadata.

## Nice-to-haves the API already exposes

The backend does Whisper live captions and LRClib synced lyrics, surfaced
through `/api/now_playing`. Cardinals play-by-play is talk content, so **live
captions are genuinely relevant** here — worth surfacing if it's cheap. Lyrics
matter mainly for music FM. Treat both as secondary to tuning + playback.

## Build & test workflow (on the Debian coding VM)

This repo is built on the headless Debian VM, driven by Claude Code over SSH.
Work inside `tmux` so a dropped SSH session doesn't kill a build or a Claude run.

```bash
./gradlew assembleDebug                 # build the debug APK
adb connect <chromebook-ip>:5555        # after enabling ADB debugging on the Chromebook
adb devices                             # confirm the target is attached
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat --pid=$(adb shell pidof io.rg2.radio)   # app logs
```

- Test target is the **Chromebook's own Android runtime** (Settings → Linux →
  Develop Android apps → Enable ADB debugging) or a **physical phone** (better
  for real-network streaming and Android Auto via the Desktop Head Unit).
- **Claude Code can build, `adb install`, and read `logcat`, but it cannot see
  the rendered UI or hear audio.** Bob is the test loop — same "the device is
  the test environment" model as the Pi. Verify on a device before committing.
- Pre-approve `gradle`/`adb`/`git` in `.claude/settings.json` to cut down on
  approval prompts (same pattern as the radio repo on the Pi).

## Conventions (match the radio/scanner repos)

- **Minimal dependencies.** Justify every library added.
- **Complete rewritten files over diffs** when handing code back to Bob.
- **Test on a device; there is no CI.** The device is the test environment.
- **Commits go out via the VM's own SSH key** (generated during VM bootstrap;
  added to GitHub).
- **Stay in your lane by default.** Don't touch the backend's `/srv/radio` /
  `/opt/sdr-tuner` or the scanner repo on a normal session. The exception is
  explicit cross-repo requests from Bob (see the intro + `backend-access`
  memory); otherwise keep the discipline.

## What's not in git (gitignore)

- `local.properties` (auto-generated `sdk.dir`).
- `.gradle/`, `build/`, `app/build/`, `*.apk`.
- Signing keystore(s) and any keystore/key passwords.
- On-device backend credentials (those live in EncryptedSharedPreferences/
  DataStore on the device, never in the repo).
- `.idea/` (if Android Studio is ever opened against this), `*.iml`.

## Remaining work / next up

Resolved during build: endpoint schemas (`docs/api.md`), tune body
(`{freq,band,hd,subchannel}`), OkHttp + kotlinx.serialization (chosen over
Retrofit), Android Auto browse tree (built; **untested on Auto**), package
`io.rg2.radio`, repo at `~/radio-android`. Still open:

1. **Settings/credentials screen** + encrypted store (EncryptedSharedPreferences/
   DataStore) to replace `InMemoryRadioSettings`; NPMplus auth still not enforced
   on `/api/tune`, but build for when it lands.
2. **Android Auto verification** via the Desktop Head Unit / a real head unit.
3. **Song-ID lifecycle** (backend): the identified track lands late and lingers
   through ads into the next song — see the `song-id-pipeline` memory for the
   problem and improvement ideas (RDS-boundary clear, re-confirmation, etc.).
