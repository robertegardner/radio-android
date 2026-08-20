# RG2 Radio — Garmin Connect IQ port notes

Port of the `:wear` Wear OS app (`wear/`, shared data layer in `core/`) to a
Connect IQ **widget + glance + background service** in Monkey C. Nothing under
`wear/`, `core/`, or `app/` was touched.

> **Status (2026-08-20):** written to compile-correct standards against the
> Connect IQ **9.2.0** SDK docs/samples, but **not compiled** — the SDK's
> compiler downloads without a login, the *device definitions* it needs
> (`~/.Garmin/ConnectIQ/Devices/*`) do not (HTTP 401 from Garmin's onboarding
> API). Expect to run the build below once; any residual type-checker nits
> will be one-liners. Everything else in this file is verified against the
> SDK's bundled device reference and the live backend contract.

---

## 1. What the Wear OS app actually does

The Wear app is **two things**: a standalone **audio player** (its own
ExoPlayer streaming Icecast MP3 over the watch's network) and a **remote
control / now-playing display** for two Raspberry-Pi backends. Only the
second half can exist on Connect IQ (see §4).

### 1.1 Endpoints it calls (from `core/.../RadioApi.kt`, `ScannerApi.kt`, `Dtos.kt`, `ScannerModels.kt`)

Base URLs come from `RadioSettings` (`InMemoryRadioSettings` defaults; no
settings screen yet): radio `https://radio.rg2.io`, scanner
`https://ems.rg2.io`, stream `https://icecast.rg2.io/fm.mp3`. `authHeader`
is an optional `Authorization: Basic …` attached to **writes only**; it is
`null` today (NPMplus auth not enforced).

| Backend | Endpoint | Method / body | Response shape (fields the watch uses) | Cadence on Wear |
|---|---|---|---|---|
| radio | `/api/now_playing` | GET | `{available, band:"fm"\|"am", freq:"100.7", mode:"lyrics"\|"captions"\|"idle", stereo, pilot, pilot_rms, pilot_blend, antenna:"Antenna A"\|…\|"HF+", fcc:{call,city,state}?, rds:{ps,rt,artist,title,pi,prog_type,freq_mhz,last_update,started_at}?, caption:{text,age_s,updated}?, track:{artist,title,album,art_url,duration,source,score,matched_at}?, lyrics:{index,lines:[{text,time_ms}],song:{…same as track}}?}` — every nested object nullable | poll **3 s** while the screen is subscribed (`WearViewModel.POLL_MS`) |
| radio | `/api/tune` | POST JSON `{freq:<number>, band:"fm"\|"am", hd:false, subchannel:0}` (+optional `stereo`, `antenna`; Wear sends neither) | `{ok:true}` · 400 `{ok:false,error}` | on favorite tap |
| radio | `/api/status` | GET | `{current_band, current_freq, current_hd, current_subchannel, bitrate, status}` | in `:core` only; Wear doesn't poll it |
| scanner | `/api/status` | GET | `{current:{name,detail}?, sdr_owner?, upcoming_passes:[{satellite,freq_mhz,aos,los,max_el}]}` — `name` is `"ems_scanner"` (MOSWIN) / `"monitor"` (aviation) / other (NOAA pass preempting); `detail` is `"active: <TG>"` during a MOSWIN call | poll **3 s**; plus a 700 ms loop for ≤22 s after a control POST |
| scanner | `/api/source/moswin` | POST, empty body | `{}` or `{error}` | MOSWIN category tap (only if job ≠ `ems_scanner`) |
| scanner | `/api/monitor/tune` | POST JSON `{freq:"132.536M", mode:"am", gain:40, label, duration_s:3600, audio_squelch:false}` | `{}` or `{error}` | aviation preset tap |
| icecast | `fm.mp3`, `ems*.mp3`, `monitor.mp3` | audio stream | MP3 | ExoPlayer — **not portable** |

Parsing is lenient everywhere (`ignoreUnknownKeys`, `coerceInputValues`).
HD fields exist but are ignored (no HD on this backend).

### 1.2 What it displays (`WearHomeScreen.kt`, `WearTheme.kt`)

One `ScalingLazyColumn`, top to bottom:

1. **Header** — `RG2 RADIO` in amber (`0xFFAE3A`) over one body line, precedence:
   `artist — title` › `title` › `station · freq` › `station` › `freq` › `"Pick a source below"`,
   where `station = rds.ps ?: fcc.call` and artist/title come from
   `track` › `lyrics.song` › `rds` (`TrackInfo.kt`). Overridden by a transient
   status message (`"Starting MOSWIN…"`, `"Tune failed"`) or `"Connecting…"`.
2. **Play/pause button** (`▶` / `❚❚`).
3. **RADIO** — 4 chips from `Favorites.SEED`: KMOX 1120 AM, KZYM 1230 AM, KGMO 100.7 FM, 95.7 FM (label + sublabel). Active chip = primary (amber) colour.
4. **MOSWIN** — header shows `MOSWIN · <talkgroup>` when `detail` starts with `active:`; 4 chips All/Police/Fire/Interop (differ only by Icecast mount).
5. **AVIATION** — 6 chips: Memphis Ctr 132.536, KCGI Tower 125.525, Approach 124.710, Center 135.500, ARTCC 127.490, ARTCC 2 128.320 (all AM, gain 40).
6. Version/`GIT_SHA` caption.

No units beyond the freq strings; no thresholds/colour logic except
active-chip highlight and the dark amber palette (bg `0x0B0B0F`, text
`0xECECEC`, dim `0x9A9AA2`, green `0x6DF09B`, red `0xE0533D`). No tiles, no
complications — the Wear app is a single activity.

### 1.3 Interactions

Tap a chip → (radio) POST tune then play stream; (MOSWIN) POST source switch
if needed, wait for job, play mount; (aviation) POST monitor tune, wait for
job, play. Play/pause → live-edge pause semantics. Scroll = rotary/swipe.
**No settings, no toggles** — base URLs are compile-time defaults.

### 1.4 Cannot map to Connect IQ

| Wear feature | Why not | Substitute |
|---|---|---|
| ExoPlayer live MP3 streaming, `MediaSessionService`, system media controls, reconnect loop | CIQ has no live-stream audio; `Media` content providers are download-to-device only | **Dropped.** The watch becomes the remote control; audio plays on the phone app / car / WiiM as before. |
| MOSWIN **category** chips (All/Police/Fire/Interop) | Only differ by stream URL | Collapsed to one action: *switch SDR to MOSWIN*. Talkgroup display kept. |
| `POST_NOTIFICATIONS`, foreground service | Android-only | n/a |
| Per-subscription polling at 3 s with `WhileSubscribed` | No coroutines; background slot is 5 min minimum | Foreground `Timer` at the configured interval (default 3 s); 5-min temporal event for the glance. |
| Post-control **job-wait loop** (700 ms × 22 s) | Would hold the widget busy | Fire-and-forget POST; the normal poll shows the job when it lands; transient "Tuning…" message for 20–25 s. |
| Album art (`art_url`), lyrics pane | Memory + no value at glance size | Dropped; caption text kept (Cardinals play-by-play). |

---

## 2. Target devices and what they dictate

Verified against the SDK 9.2.0 device reference (`doc/docs/Device_Reference/*.html`)
and https://developer.garmin.com/connect-iq/compatible-devices/.

| Product ID | Covers | Screen | Display | CIQ | Widget / Glance / Background mem |
|---|---|---|---|---|---|
| `fenix6s`, `fenix6spro` | 6S, 6S Pro/Sapphire/Solar | 240×240 | MIP 64-col | 3.4 | 64 KB / 32 KB / 32 KB (6S) · 1 MB / 32 / 32 (6S Pro) |
| `fenix6`, `fenix6pro` | 6, 6 Solar, 6 Pro/Sapphire, quatix 6 | 260×260 | MIP 64-col | 3.4 | 64 KB / 32 / 32 (6) · 1 MB / 32 / 32 (Pro) |
| `fenix6xpro` | 6X Pro/Sapphire/Solar, tactix Delta, quatix 6X | 280×280 | MIP 64-col | 3.4 | 1 MB / 32 / 32 |
| `descentmk2` | **Mk2 and Mk2i** (one ID) | 280×280 | MIP 64-col | 3.4 | 1 MB / 32 / 32 |
| `descentmk2s` | Mk2 S | 240×240 | MIP 64-col | 3.4 | 1 MB / 32 / 32 |
| `descentmk343mm` | **Mk3 and Mk3i 43 mm** (one ID) | **390×390** | AMOLED | 5.1 | 768 KB / 64 / 64 |
| `descentmk351mm` | Mk3i 51 mm | 454×454 | AMOLED | 5.1 | 768 KB / 64 / 64 |

Notes vs. the brief: there is no `descentmk3` ID — Mk3 is split by case size,
and the 43 mm is 390×390 (so a `resources-round-390x390` folder was added
alongside 240/260/280/454). Mk2i has no separate ID.

Consequences baked in:

- **`minApiLevel="3.4.0"`** (fenix 6 / Mk2 ceiling). No System 6/7 APIs used at
  all — no scalable fonts, no complications, no `Graphics.createBufferedBitmap`
  — so no `has` checks were needed; the only runtime branch is MIP vs AMOLED
  (screen width ≥ 390 → AMOLED) for the dim-grey shade.
- **Memory floor = fenix 6 / 6S base: widget 64 KB, glance 32 KB, background 32 KB.**
  Hence: snapshot is a flat `Array` of primitives (indices in `Snap`), raw JSON
  dictionaries are dropped (`data = null`) right after field extraction, caption
  is truncated to 90 chars, catalogs are functions (allocated only while a menu
  is open), one 6-label layout is reused by all pages, and only
  `App/Fetcher/Api/Model/Settings/BackgroundService` are `(:background)`, only
  `App/Model/Settings/Ui/RadioGlanceView` are `(:glance)`.
- **`/api/now_playing` carries the full synced-lyrics array.** In a 32 KB
  background slot that can trip `-402 NETWORK_RESPONSE_TOO_LARGE` /
  `-403 NETWORK_RESPONSE_OUT_OF_MEMORY`; `Fetcher` then falls back to the tiny
  `/api/status` so the glance still shows band/freq. (A `?lite=1` variant of
  `now_playing` on the backend would remove the fallback — backend work, not done.)
- **Glance lifecycle on fenix 6 is "background UI update":** the system
  relaunches the app every ≥ 30 s to repaint the glance; `requestUpdate()` is a
  no-op there. So the glance reads **only Storage** and makes no requests.
- **Colours** are all on the 64-colour MIP lattice (channels ∈ {00,55,AA,FF})
  so fenix 6 renders them exactly; AMOLED gets the same on true black.

### ⚠ Plain HTTP on a real watch

The brief asks for plain `http://` to LAN hosts. Garmin devices **refuse
non-HTTPS `makeWebRequest`** with `-1001 SECURE_CONNECTION_REQUIRED` (the
simulator has *Settings → Use Device HTTPS Requirements* to relax this for
testing only). The code accepts any URL and maps `-1001` to the on-screen
message *"Watch requires https"*, and the defaults are the backends' real
TLS hostnames (`https://radio.rg2.io`, `https://ems.rg2.io` — what the Wear
app uses) rather than an `http://homelab.local:PORT` placeholder that would
fail on-device. For a LAN-only setup put the Pi behind a cert the phone
trusts (the existing NPMplus/Let's Encrypt path already does this).

---

## 3. Architecture → file map

```
garmin/
  manifest.xml                 widget, minApiLevel 3.4.0, 9 products, Background + Communications
  monkey.jungle                strict typecheck; per-product resourcePath → resolution folders
  resources/                   strings, settings.xml + properties.xml, 40 px launcher icon, base layout
  resources-round-240x240/     layout positions (fenix6s/6spro, mk2s)
  resources-round-260x260/     (fenix6/6pro)
  resources-round-280x280/     (fenix6xpro, mk2/mk2i)
  resources-round-390x390/     + 60 px icon (mk3/mk3i 43 mm)
  resources-round-454x454/     + 60 px icon (mk3i 51 mm)
  source/
    App.mc                     AppBase: glance/widget/service entry, temporal event (un)registration,
                               onBackgroundData → Storage, onSettingsChanged
    GlanceView.mc              Ui palette/fit/fonts + RadioGlanceView (Storage-only)
    MainView.mc                4 pages, foreground poll timer, control responses, drawing
    MainDelegate.mc            up/down/swipe = pages, START = select, MENU = refresh
    SourceMenu.mc              Menu2 lists for favorites + aviation presets
    BackgroundService.mc       ServiceDelegate.onTemporalEvent → Fetcher → Background.exit(snapshot)
    Api.mc                     Api.get (bg-safe), Control.tune/selectMoswin/tuneMonitor (fg), Fetcher
    DataModel.mc               Snap indices, Model.parse* (exact JSON shapes), headline/age/staleness, errorText
    Settings.mc                Properties reads at request time, Basic-auth header
    Catalog.mc                 favorites + aviation presets (mirrors core Favorites/ScannerCatalog)
```

### Wear OS → Connect IQ mapping

| Wear OS component | Connect IQ equivalent |
|---|---|
| `WearApp` / `WearContainer` (hand-rolled DI) | `RadioApp` (AppBase) + module functions — no DI |
| `RadioSettings` / `InMemoryRadioSettings` | `Settings` module over `Application.Properties` (**now user-configurable**: URLs, auth, refresh, poll, scanner toggle) |
| `RadioApi` + `ScannerApi` (OkHttp) | `Api.get` / `Control.*` over `Communications.makeWebRequest` |
| `Dtos.kt` / `ScannerModels.kt` (kotlinx.serialization) | `Model.parseNowPlaying` / `parseStatus` / `parseScanner` into the `Snap` array |
| `TrackInfo.kt` precedence | `Model.parseNowPlaying` (track › lyrics.song › rds) + `Model.headline` |
| `NowPlayingRepository` / `ScannerRepository` 3 s polls | `MainView` `Timer` at `pollSec`; `Fetcher` does one radio+scanner cycle |
| `WearViewModel` state + messages | `MainView` fields (`_snap`, `_busy`, `_msg`) |
| `NowPlayingHeader` | **Glance** (headline + age) and **page 0** (station · freq / artist / title or caption / STEREO·ANT) |
| RADIO chips + `Favorites.SEED` | page 1 + `SourceMenu.pushFavorites` → `Control.tune` (`>` marks the tuned favorite) |
| MOSWIN header + category chips | page 2: job state + talkgroup; SELECT → `Control.selectMoswin` |
| AVIATION chips + `ScannerCatalog.PRESETS` | page 3 + `SourceMenu.pushAviation` → `Control.tuneMonitor` |
| `WearPlaybackService` / `LiveStreamPlayer` / play button | **dropped** (no live audio on CIQ) |
| `WearTheme` amber palette | `Ui` constants, MIP-safe |
| — (none) | **Background temporal event (5 min)** keeping the glance fresh |

Behavioural details preserved from the Wear code: freq is sent in the units
`/api/tune` expects (MHz for FM, kHz for AM) — as a **string**, since the
backend `str()`s it and a Monkey C `Float` would serialise 100.7 as
100.699997; the aviation body is byte-for-byte the `/listen` page payload;
`active:` prefix parsing for the talkgroup; 400 bodies are parsed and their
`error` shown. Empty states: `"Nothing tuned"`, `"no track ID"`,
`"Scanner idle"`, `"Monitor off"`, `"No data yet"`; every request outcome
(`-104`, `-300`, `-402/-403`, `-1001`, 4xx, 5xx, …) renders via
`Model.errorText` on the status line / glance — never blank.

---

## 4. Build, run, sideload

### Install the SDK (once)

1. Download the **Connect IQ SDK Manager** from
   https://developer.garmin.com/connect-iq/sdk/ and sign in with the Garmin
   account — this is what fetches the **device files** into
   `~/.Garmin/ConnectIQ/Devices/`. Install SDK 9.x and tick the nine devices
   above. (The compiler zip alone —
   `https://developer.garmin.com/downloads/connect-iq/sdks/sdks.json` — is
   not enough; `monkeyc` exits 103 "device not found" without them.)
2. `export CIQ=~/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-9.2.0-*/bin` (or the
   `sdks` path the manager prints) and add it to `PATH`. JDK 21 is already on
   this VM (`java -version`).
3. Developer key (kept **out of git** — `garmin/.gitignore`):
   ```bash
   cd garmin
   openssl genrsa -out developer_key.pem 4096
   openssl pkcs8 -topk8 -inform PEM -outform DER -in developer_key.pem -out developer_key.der -nocrypt
   ```

### Compile (strict type checking)

```bash
cd garmin
# one device — fenix6 is the tightest (64 KB widget / 32 KB glance+background)
monkeyc -f monkey.jungle -d fenix6 -o bin/rg2radio-fenix6.prg -y developer_key.der -l 2 -w
# every target in the manifest
for d in fenix6 fenix6s fenix6pro fenix6spro fenix6xpro descentmk2 descentmk2s descentmk343mm descentmk351mm; do
  monkeyc -f monkey.jungle -d "$d" -o "bin/rg2radio-$d.prg" -y developer_key.der -l 2 -w || break
done
# store package (all devices in one .iq)
monkeyc -f monkey.jungle -e -o bin/rg2radio.iq -y developer_key.der -r
```
`-l 2` = informative type checks; `project.typecheck = strict` in the jungle
already applies level 3. `-w` shows warnings. Memory use per build is printed
in the simulator's *File → View Memory*.

### Simulator

```bash
connectiq &                                   # start the simulator (bin/connectiq)
monkeydo bin/rg2radio-fenix6.prg fenix6       # load + run the widget
```
Then in the simulator: *Settings → Trigger App Settings* to edit the
properties (URLs, auth, poll). No phone is involved: the simulator proxies
`makeWebRequest` through the host's network.

### Sideload to a watch

Connect the watch over USB (mass storage) and copy the device-matching
`.prg` to **`/GARMIN/APPS/`** (the `Primary` volume; on Descent Mk3 it's the
same path). Eject; the widget appears in the glance list (fenix 6: hold
UP → *Glances → Add*). App settings for a sideloaded build are edited in the
Connect IQ mobile app (*Device → Connect IQ → My Apps → RG2 Radio → Settings*)
once the watch has synced — sideloaded apps do appear there. Logs (`System.println`) land in
`/GARMIN/APPS/LOGS/<prgname>.TXT` if you create an empty file of that name
first.

---

## 5. Simulator test plan

1. **Cold start** — fresh sim, open the glance list: glance shows
   `RG2 RADIO  --` / `No data yet`; open widget: page 0 `Loading...` then a
   station line within a few seconds; status `updated now`.
2. **Pages** — UP/DOWN (or swipe on Mk3 profile) cycles NOW → RADIO → MOSWIN →
   AVIATION → NOW. Toggle *showScanner* off in app settings: only 2 pages, and
   the current page clamps to RADIO if you were on 2–3.
3. **Tune** — RADIO page → START → Menu2 → KGMO 100.7: status `Tuning KGMO
   100.7...`, then `Sent OK`; within one poll page 0 shows `KGMO · 100.7 FM`
   and the RADIO page marks `> KGMO 100.7`. Pick KMOX: `1120 AM` (no RDS on
   AM → no `ps`; `fcc.call` fills `KMOX` when the backend has it).
4. **Rejected write** — set *Radio server URL* to `https://radio.rg2.io/x`
   (404) and tune: `Not found - check URL`. Point at a host that returns
   400 with `{ok:false,error:"missing freq"}`: `Rejected: missing freq`.
5. **Scanner** — MOSWIN page shows `P25 ACTIVE` + talkgroup when
   `current.name == ems_scanner`; START → `Switching to MOSWIN...` → `Sent OK`.
   AVIATION → START → *KCGI Tower* → `Tuning KCGI Tower...`; the page flips to
   `MONITOR ON` + the freq once the scanner status reports `monitor`.
6. **Captions** — while the backend is in `mode: captions` (talk radio /
   Cardinals), page 0 line 2 reads `LIVE CAPTION` (amber) and line 3 the
   caption text, truncated to the chord width with `...`.
7. **Network failure space** — simulator *Settings → Connection → disable*
   (or pull the host offline): status line turns red `Phone not connected`
   (−104) / `Request timed out` (−300); the last good data stays on screen;
   the glance keeps its last snapshot with the age climbing `3m`, `7m`
   (amber), `21m` (red). Re-enable: next poll recovers without restarting.
8. **HTTPS requirement** — enter `http://…` as the radio URL with *Use Device
   HTTPS Requirements* ticked: `Watch requires https`. Untick: works (sim only).
9. **Background temporal event** — close the widget (BACK), then in the
   simulator use **Settings → Trigger Background Event → Temporal Event**
   (or *Simulation → Background Events*). Reopen the glance list: the age
   resets to `now` and the headline updates without opening the widget.
   Toggle *bgRefresh* off → *File → View Background* shows no event
   registered; on → registered at 5 min.
10. **Too-large response** — not directly triggerable in the sim; verify the
    fallback path by pointing *Radio server URL* at a host whose
    `/api/now_playing` 404s but `/api/status` works: page 0 shows band/freq
    only with status `Not found - check URL`. (On a fenix 6, watch for
    `Response too large` in the status line — that's −402/−403 taking the
    `/api/status` path, which still yields band/freq.)
11. **Memory** — *File → View Memory* on the `fenix6` profile in widget,
    glance, and background modes; all three must sit well under 64/32/32 KB
    peak. If the background peak is close, shorten the caption cap in
    `Model.parseNowPlaying` first.

---

## 6. Open items / follow-ups

- Run the build on a machine with the SDK Manager (needs a Garmin login) and
  fix whatever the strict checker reports; nothing here is expected to be
  structural.
- Backend (`github.com/robertegardner/radio`, on explicit request only): a
  `GET /api/now_playing?lite=1` without `lyrics.lines` would let the 32 KB
  background slot always get artist/title.
- If Bob wants the watch to *start* audio somewhere, the natural hook is a
  backend endpoint the WiiM/phone listens to — out of scope here.
