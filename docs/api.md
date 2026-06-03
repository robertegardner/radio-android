# Backend API reference

Verified against the live server on **2026-06-02** by curling the real
endpoints. The field names here are the *observed* ones — if they ever drift,
the live server is the source of truth, not this file. Re-verify with:

```bash
curl -s https://radio.rg2.io/api/now_playing
curl -s https://radio.rg2.io/api/stations
curl -s https://radio.rg2.io/api/status
```

- **Base URL:** `https://radio.rg2.io`
- **Stream:** `https://icecast.rg2.io/fm.mp3` (MP3, mono, ~128k)
- **Auth:** Read endpoints are public. `/api/tune` is *intended* to sit behind
  NPMplus basic auth but **that auth is not live yet** — an unauthenticated
  `POST /api/tune` returns `400 missing freq`, not `401`. Build as if auth will
  arrive (optional `Authorization` header), but tuning works today without it.

---

## `GET /api/now_playing` — poll ~1s

The single feed for the now-playing UI. Note the data is **nested objects**, not
the flat fields the original CLAUDE.md notes guessed at.

```jsonc
{
  "available": true,
  "band": "fm",                    // "fm" | "am"   (NOT "wbfm")
  "freq": "100.7",                 // string
  "mode": "lyrics",                // "lyrics" | "captions" | "idle" — drives the caption/lyric pane
  "subchannel": 0,
  "hd": false,                     // HD is dead on this backend; all hd_* read false/unavailable
  "hd_locked": false,
  "hd_probing": false,
  "hd_unavailable": false,

  "fcc": {                         // FCC license lookup for the tuned freq
    "call": "KGMO",
    "city": "Cape Girardeau",
    "state": "MO"
  },

  "rds": {                         // live RDS decode
    "ps": "Ozzy",                  // Program Service name (short station name)
    "rt": "The Classic Rock Sta",  // RadioText
    "artist": null,                // often null — real song data is under lyrics.song
    "title": null,
    "pi": "0x211E",                // Program Identification
    "prog_type": "Drama",          // PTY
    "freq_mhz": "100.7",
    "last_update": 1780438540.79,  // unix epoch seconds (float)
    "started_at": 1780419873.81
  },

  "caption": {                     // Whisper live captions — relevant for Cardinals play-by-play
    "text": "Mifigata Chucho Hong Kong ...",
    "age_s": 176.5,                // seconds since this caption text was produced
    "updated": 1780438364.87       // unix epoch seconds
  },

  "lyrics": {                      // LRClib synced lyrics (music FM)
    "index": 27,                   // index into lines[] of the currently-active line
    "lines": [
      { "text": "Keep your eyes on the road", "time_ms": 23770 }
      // ... synced LRC lines, time_ms from song start
    ],
    "song": {
      "artist": "The Doors",       // authoritative song artist/title live HERE, not in rds
      "title": "Roadhouse Blues",
      "duration": 247.0,           // seconds
      "source": "rds",
      "score": null,
      "matched_at": 1780438365.88
    }
  }
}
```

**Client notes**
- For "what song is playing", prefer `lyrics.song.{artist,title}`; `rds.{artist,title}` are frequently `null`.
- `mode` selects the secondary pane: `captions` → show `caption.text`; `lyrics` → show synced `lyrics.lines` highlighting `lyrics.index`; `idle` → show neither.
- `caption`, `lyrics`, `lyrics.song`, and `fcc` may all be absent/null depending on band and state — make every nested object optional/nullable.
- Ignore `hd_*` — no HD UI (per CLAUDE.md).

---

## `GET /api/stations` — poll ~30s

Two arrays of scanned stations, each with an ISO-8601 scan timestamp.
**Key gotcha: AM entries use `freq_khz` (integer kHz); FM entries use `freq_mhz`
(float MHz).** They are otherwise identical.

```jsonc
{
  "fm": [
    {
      "call": "KGMO",
      "city": "Cape Girardeau",
      "state": "MO",
      "freq_mhz": 100.7,           // FM: MHz (float)
      "label": "KGMO (Cape Girardeau, MO)",
      "power_db": -13.8,
      "snr_db": 28.3
    }
    // ...
  ],
  "fm_scanned_at": "2026-05-19T19:37:31",   // ISO-8601, no timezone suffix

  "am": [
    {
      "call": "KMOX",
      "city": "St. Louis",
      "state": "MO",
      "freq_khz": 1120,            // AM: kHz (integer)
      "label": "KMOX (St. Louis, MO)",
      "power_db": -52.4,
      "snr_db": 25.6
    }
    // ...
  ],
  "am_scanned_at": "2026-05-19T19:37:43"
}
```

**Client notes**
- KMOX 1120 AM (Cardinals flagship) and KGMO 100.7 FM are present in live scans.
- Scans are time/geography dependent — the seed favorites (KZYM 1230, 95.7 FM)
  may not appear in every scan. Don't assume a favorite is in the list.
- Sort by `snr_db` or `power_db` for signal-quality ordering if desired.

---

## `POST /api/tune` — on user action

Confirmed from the `/radio` web UI's own `tuneTo()` JS. **The body key is
`band` (`"fm"`/`"am"`), not `mode`/`wbfm` as the original notes guessed.**

Request body:
```jsonc
{
  "freq": 100.7,        // number; FM in MHz, AM in kHz (matches the station's freq_mhz/freq_khz)
  "band": "fm",         // "fm" | "am"
  "hd": false,          // always false for this backend
  "subchannel": 0       // always 0
}
```

Responses:
```jsonc
{ "ok": true }
{ "ok": false, "error": "missing freq" }   // 400 on bad/empty body
```

- `Allow: POST, OPTIONS`. A `GET` returns `405`.
- Writing this restarts the stream on the backend; expect a tune to settle over
  the next second or two (poll `now_playing`/`status` to confirm).
- When auth lands, attach `Authorization` here only.

---

## `GET /api/status` — lightweight current-tuning state

Undocumented in the original notes; the web UI polls it. Much lighter than
`now_playing` — useful for confirming a tune took effect.

```jsonc
{
  "current_band": "fm",
  "current_freq": "100.7",
  "current_hd": false,
  "current_subchannel": 0,
  "status": "active"
}
```
