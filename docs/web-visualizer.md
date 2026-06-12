# Web-UI Butterchurn visualizer (backend `/radio` page) — and what it means here

Shipped in the **backend** repo 2026-06-12 (`radio` commits `1a8e5fe` +
`3c4e2f3`): the listener web UI at `https://radio.rg2.io/radio` now has a
MilkDrop-style visualizer built on **Butterchurn** (WebGL2, MIT). This is a
**web-page feature only** — this app never renders `radio.html`, so it does
not and cannot appear in the app. The app's own visualizers
(BARS/PEAKS/MIRROR/SCOPE/FRACTAL/SYNTH in `app/.../ui/Visualizers.kt`, FFT via
`RECORD_AUDIO`) are unrelated and unaffected.

## How the web one works (reference for a potential port)

- Vendored bundles **butterchurn 2.6.7** + **butterchurn-presets 2.4.7**
  (base pack) served by Flask at `/static/*.js`; all logic lives inline in
  `templates/radio.html` (backend repo, `~/projects/radio`).
- Audio graph: the `<audio>` element → one-time `createMediaElementSource()`
  → always-connected `destination` + `visualizer.connectAudio()`. Render loop
  is rAF, paused when the viz is off / tab hidden / audio paused. Random
  preset on enable, 30 s auto-advance (2.7 s blend), double-tap = next preset,
  single tap = fullscreen. Per-band persistence (`sdr-radio.viz.fm/.am`);
  AM defaults off.
- **CORS dependency (web-only, but don't break it):** the `<audio>` element is
  now `crossorigin="anonymous"`, which only plays because
  `https://icecast.rg2.io/fm.mp3` serves `Access-Control-Allow-Origin: *` —
  emitted by **Icecast itself** (the rack instance), proxied through NPMplus.
  Exactly one copy of the header is required: removing it breaks web playback
  outright, adding a second copy at the proxy breaks it too. Irrelevant to
  this app's native player (Media3 doesn't do CORS), but anyone touching the
  NPMplus icecast host config must preserve it.

## If MilkDrop-style visuals are ever wanted in the app

Two realistic routes, neither trivial:

1. **projectM** (`projectM-android` / libprojectM, the native MilkDrop
   implementation) rendering to a `GLSurfaceView`, fed PCM/FFT the same way
   `Visualizers.kt` is fed today (`android.media.audiofx.Visualizer` on the
   Media3 audio session — already permission-gated by `RECORD_AUDIO`).
   Native library + NDK build; the presets are the same MilkDrop `.milk`
   ecosystem Butterchurn uses.
2. **WebView wrapping `/radio`** — cheap but wrong for this app: it would
   double-play audio (page `<audio>` + Media3) unless the page grew a
   "viz-only, no audio" mode, and it gives up the native player. Not
   recommended given the Android-Auto/background-playback rationale for going
   native in the first place.

Until one of those is a real priority, the answer to "why doesn't the app show
the MilkDrop visuals?" is simply: they live in the web page, and the app
doesn't use the web page.
