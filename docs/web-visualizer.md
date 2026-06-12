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

## MilkDrop in the app — DONE (2026-06-12): native projectM port

Route 1 below was implemented the same day as this note. The app now has a
**MILKDROP** style in the visualizer chip row, rendered by **libprojectM
v4.1.6** (git submodule at `third_party/projectm`, statically linked into
`libprojectm-jni.so`) on a GLES3 `GLSurfaceView`, fed mono PCM by the same
`android.media.audiofx.Visualizer` session tap the other reactive styles use.
Same UX as the web one: random preset on start, 30 s auto-advance with a
2.7 s blend, tap the pane for the next preset. 16 classic shader-less
presets (Geiss/Rovastar/Unchained/…) ship in `assets/milkdrop/` and are
extracted to `filesDir` on first use. The chip hides itself when GLES3 or
the native lib is unavailable (`supportsGles3` + `ProjectMNative.available`).

Key files: `app/src/main/cpp/{CMakeLists.txt,projectm_jni.cpp}`,
`app/src/main/java/io/rg2/radio/viz/{ProjectMNative,MilkdropVisualizer}.kt`.
Build gotchas are recorded in CLAUDE.md ("Native build (MILKDROP)").

The rejected alternative, kept for the record:

- **WebView wrapping `/radio`** — cheap but wrong for this app: it would
  double-play audio (page `<audio>` + Media3) unless the page grew a
  "viz-only, no audio" mode, and it gives up the native player and the
  Android-Auto/background-playback rationale for going native.
