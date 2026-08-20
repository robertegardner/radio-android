import Toybox.Application;
import Toybox.Lang;
import Toybox.Time;

// The snapshot is a flat Array of primitives, NOT a Dictionary, and NOT the
// raw JSON: it's what the background service hands to Background.exit(), what
// lives in Storage, and what the 32 KB glance draws from. Index constants are
// inlined by the compiler, so this costs no memory at run time.
(:background) (:glance)
module Snap {
    const T       = 0;   // Number  - epoch seconds the fetch started
    const RCODE   = 1;   // Number  - radio backend response code (200 = good)
    const BAND    = 2;   // String? - "fm" | "am"
    const FREQ    = 3;   // String? - "100.7" (MHz) or "1120" (kHz), as the backend sends it
    const STATION = 4;   // String? - rds.ps, falling back to fcc.call
    const ARTIST  = 5;   // String? - track.artist > lyrics.song.artist > rds.artist
    const TITLE   = 6;   // String? - same precedence for title
    const PILOT   = 7;   // Boolean - 19 kHz pilot lock (the STEREO LED)
    const ANT     = 8;   // String? - "Antenna A" | "Antenna B" | "Antenna C" | "HF+"
    const MODE    = 9;   // String? - "lyrics" | "captions" | "idle"
    const CAPTION = 10;  // String? - caption.text, truncated
    const CAPAGE  = 11;  // Number? - caption.age_s, whole seconds
    const SCODE   = 12;  // Number  - scanner backend response code (0 = not fetched)
    const SJOB    = 13;  // String? - scanner current.name ("ems_scanner" | "monitor" | other)
    const SDETAIL = 14;  // String? - scanner current.detail ("active: <TG>" | freq | ...)
    const SIZE    = 15;

    const KEY = "snap";  // Storage key
}

(:background) (:glance)
module Model {

    typedef Snapshot as Array<Application.PropertyValueType>;

    function empty() as Snapshot {
        var s = new [Snap.SIZE] as Snapshot;
        s[Snap.T] = Time.now().value();
        s[Snap.RCODE] = 0;
        s[Snap.PILOT] = false;
        s[Snap.SCODE] = 0;
        return s;
    }

    // Safe string read: tolerates absent keys, nulls, numbers-as-strings, "".
    function str(d as Dictionary, key as String) as String? {
        var v = d[key];
        if (v instanceof String) {
            return v.length() > 0 ? v : null;
        }
        if (v instanceof Number || v instanceof Float) {
            return v.toString();
        }
        return null;
    }

    // GET /api/now_playing  (shape: docs/api.md, mirrors core Dtos.kt)
    function parseNowPlaying(d as Dictionary, s as Snapshot) as Void {
        s[Snap.BAND] = str(d, "band");
        s[Snap.FREQ] = str(d, "freq");
        s[Snap.MODE] = str(d, "mode");
        s[Snap.PILOT] = (d["pilot"] == true);
        s[Snap.ANT] = str(d, "antenna");

        var rds = d["rds"];
        var station as String? = null;
        if (rds instanceof Dictionary) {
            station = str(rds, "ps");
        }
        if (station == null) {
            var fcc = d["fcc"];
            if (fcc instanceof Dictionary) {
                station = str(fcc, "call");
            }
        }
        s[Snap.STATION] = station;

        // Same precedence as core TrackInfo.kt: track > lyrics.song > rds.
        var artist as String? = null;
        var title as String? = null;
        var track = d["track"];
        if (track instanceof Dictionary) {
            artist = str(track, "artist");
            title = str(track, "title");
        }
        if (title == null) {
            var ly = d["lyrics"];
            if (ly instanceof Dictionary) {
                var song = ly["song"];
                if (song instanceof Dictionary) {
                    artist = str(song, "artist");
                    title = str(song, "title");
                }
            }
        }
        if (title == null && rds instanceof Dictionary) {
            artist = str(rds, "artist");
            title = str(rds, "title");
        }
        s[Snap.ARTIST] = artist;
        s[Snap.TITLE] = title;

        var cap = d["caption"];
        if (cap instanceof Dictionary) {
            var text = str(cap, "text");
            if (text != null && text.length() > 90) {
                text = text.substring(0, 90);
            }
            s[Snap.CAPTION] = text;
            var age = cap["age_s"];
            if (age instanceof Number) {
                s[Snap.CAPAGE] = age;
            } else if (age instanceof Float) {
                s[Snap.CAPAGE] = age.toNumber();
            }
        }
    }

    // GET /api/status - the tiny fallback when now_playing is too large for
    // the background process (-402 / -403). Only band + freq are known.
    function parseStatus(d as Dictionary, s as Snapshot) as Void {
        s[Snap.BAND] = str(d, "current_band");
        s[Snap.FREQ] = str(d, "current_freq");
    }

    // Scanner GET /api/status  (shape: core ScannerModels.kt)
    function parseScanner(d as Dictionary, s as Snapshot) as Void {
        var cur = d["current"];
        if (cur instanceof Dictionary) {
            s[Snap.SJOB] = str(cur, "name");
            var detail = str(cur, "detail");
            if (detail != null && detail.length() > 40) {
                detail = detail.substring(0, 40);
            }
            s[Snap.SDETAIL] = detail;
        }
    }

    // ---- presentation helpers shared by glance + widget -------------------

    // Station line: "KGMO · 100.7 FM" / "1120 AM" / null.
    function stationLine(s as Snapshot) as String? {
        var freq = s[Snap.FREQ] as String?;
        var band = s[Snap.BAND] as String?;
        var station = s[Snap.STATION] as String?;
        var f as String? = null;
        if (freq != null) {
            f = (band != null) ? freq + " " + band.toUpper() : freq;
        }
        if (station != null && f != null) {
            return station + " · " + f;
        }
        return station != null ? station : f;
    }

    // The single most important line (same precedence as the Wear header):
    // artist — title, else title, else station · freq, else a hint.
    function headline(s as Snapshot?) as String {
        if (s == null) {
            return "No data yet";
        }
        var title = s[Snap.TITLE] as String?;
        var artist = s[Snap.ARTIST] as String?;
        if (title != null) {
            return artist != null ? artist + " - " + title : title;
        }
        var st = stationLine(s);
        if (st != null) {
            return st;
        }
        var code = s[Snap.RCODE] as Number;
        return code == 200 ? "Nothing tuned" : errorText(code);
    }

    // "now" / "3m" / "2h" - age of the snapshot.
    function ageText(s as Snapshot?) as String {
        if (s == null) {
            return "--";
        }
        var age = Time.now().value() - (s[Snap.T] as Number);
        if (age < 60) {
            return "now";
        }
        if (age < 3600) {
            return (age / 60).toString() + "m";
        }
        return (age / 3600).toString() + "h";
    }

    // 0 fresh (<= 6 min: one missed background slot), 1 aging, 2 stale.
    function staleness(s as Snapshot?) as Number {
        if (s == null) {
            return 2;
        }
        var age = Time.now().value() - (s[Snap.T] as Number);
        if (age <= 360) {
            return 0;
        }
        return age <= 1200 ? 1 : 2;
    }

    // Human-readable text for every makeWebRequest outcome: Garmin's negative
    // transport codes plus HTTP statuses. Never leaves the screen blank.
    function errorText(code as Number) as String {
        if (code == 200) { return "OK"; }
        if (code == 0) { return "Not fetched"; }
        if (code == -104) { return "Phone not connected"; }
        if (code == -1 || code == -4 || code == -103) { return "Bluetooth error"; }
        if (code == -2 || code == -3) { return "Phone timeout"; }
        if (code == -101) { return "Request queue full"; }
        if (code == -300) { return "Request timed out"; }
        if (code == -402 || code == -403) { return "Response too large"; }
        if (code == -400 || code == -401 || code == -1002) { return "Bad response (not JSON)"; }
        if (code == -1001) { return "Watch requires https"; }
        if (code == -5 || code == -1003) { return "Request cancelled"; }
        if (code == 400) { return "Rejected (400)"; }
        if (code == 401 || code == 403) { return "Auth required (" + code + ")"; }
        if (code == 404) { return "Not found - check URL"; }
        if (code >= 500) { return "Server error " + code; }
        if (code > 0) { return "HTTP " + code; }
        return "Error " + code;
    }
}
