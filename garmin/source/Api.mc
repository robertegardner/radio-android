import Toybox.Communications;
import Toybox.Lang;
import Toybox.Time;

// Thin makeWebRequest wrappers. Everything goes through the phone (BLE -> GCM
// -> internet/LAN); the response is JSON-decoded by the system before the
// callback runs, so we never hold the raw body.
(:background)
module Api {

    function get(url as String, cb as Method(code as Number, data as Dictionary or String or Null) as Void) as Void {
        Communications.makeWebRequest(
            url,
            null,
            {
                :method => Communications.HTTP_REQUEST_METHOD_GET,
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
            },
            cb
        );
    }
}

// Write endpoints - foreground only (keeps the background image small).
module Control {

    function post(url as String, body as Dictionary, cb as Method(code as Number, data as Dictionary or String or Null) as Void) as Void {
        var headers = {
            "Content-Type" => Communications.REQUEST_CONTENT_TYPE_JSON
        } as Dictionary<String, Object>;
        var auth = Settings.authHeader();
        if (auth != null) {
            headers["Authorization"] = auth;
        }
        Communications.makeWebRequest(
            url,
            body,
            {
                :method => Communications.HTTP_REQUEST_METHOD_POST,
                :headers => headers,
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
            },
            cb
        );
    }

    // POST /api/tune {freq, band, hd, subchannel}. freq goes as a STRING: the
    // backend str()s it anyway, and a Monkey C Float would serialise 100.7 as
    // 100.699997.
    function tune(band as String, freq as String, cb as Method(code as Number, data as Dictionary or String or Null) as Void) as Void {
        post(
            Settings.radioUrl() + "/api/tune",
            { "freq" => freq, "band" => band, "hd" => false, "subchannel" => 0 },
            cb
        );
    }

    // POST /api/source/moswin - switch the shared scanner SDR to MOSWIN P25.
    function selectMoswin(cb as Method(code as Number, data as Dictionary or String or Null) as Void) as Void {
        post(Settings.scannerUrl() + "/api/source/moswin", {}, cb);
    }

    // POST /api/monitor/tune - aviation AM; exact body the /listen page sends.
    function tuneMonitor(freq as String, label as String, cb as Method(code as Number, data as Dictionary or String or Null) as Void) as Void {
        post(
            Settings.scannerUrl() + "/api/monitor/tune",
            {
                "freq" => freq,
                "mode" => "am",
                "gain" => 40,
                "label" => label,
                "duration_s" => 3600,
                "audio_squelch" => false
            },
            cb
        );
    }
}

// One refresh cycle: radio now_playing (falling back to /api/status if the
// response is too big for this process), then scanner status, then done.
// Used identically by the background service and the open widget.
(:background)
class Fetcher {
    private var _snap as Model.Snapshot;
    private var _done as Method(snap as Model.Snapshot) as Void;

    function initialize(done as Method(snap as Model.Snapshot) as Void) {
        _done = done;
        _snap = Model.empty();
    }

    function start() as Void {
        Api.get(Settings.radioUrl() + "/api/now_playing", method(:onRadio));
    }

    function onRadio(code as Number, data as Dictionary or String or Null) as Void {
        _snap[Snap.RCODE] = code;
        if (code == 200 && data instanceof Dictionary) {
            Model.parseNowPlaying(data, _snap);
        } else if (code == -402 || code == -403) {
            // now_playing carries the full synced-lyrics array; in a 32 KB
            // background slot that can overflow. Settle for band/freq.
            data = null;
            Api.get(Settings.radioUrl() + "/api/status", method(:onStatus));
            return;
        }
        data = null;
        next();
    }

    function onStatus(code as Number, data as Dictionary or String or Null) as Void {
        if (code == 200 && data instanceof Dictionary) {
            Model.parseStatus(data, _snap);
            _snap[Snap.RCODE] = 200;
        }
        data = null;
        next();
    }

    private function next() as Void {
        if (Settings.showScanner()) {
            Api.get(Settings.scannerUrl() + "/api/status", method(:onScanner));
        } else {
            _done.invoke(_snap);
        }
    }

    function onScanner(code as Number, data as Dictionary or String or Null) as Void {
        _snap[Snap.SCODE] = code;
        if (code == 200 && data instanceof Dictionary) {
            Model.parseScanner(data, _snap);
        }
        data = null;
        _done.invoke(_snap);
    }
}
