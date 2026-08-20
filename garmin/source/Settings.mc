import Toybox.Application;
import Toybox.Lang;
import Toybox.StringUtil;

// App settings (resources/settings). Read at request time on purpose - the
// user can change the server URL from Garmin Connect while the widget's
// background service keeps running, and Properties is the live source.
(:background) (:glance)
module Settings {

    const DEFAULT_RADIO_URL = "https://radio.rg2.io";
    const DEFAULT_SCANNER_URL = "https://ems.rg2.io";

    function radioUrl() as String {
        return trimSlash(stringOf("radioBaseUrl", DEFAULT_RADIO_URL));
    }

    function scannerUrl() as String {
        return trimSlash(stringOf("scannerBaseUrl", DEFAULT_SCANNER_URL));
    }

    function bgRefresh() as Boolean {
        return boolOf("bgRefresh", true);
    }

    function showScanner() as Boolean {
        return boolOf("showScanner", true);
    }

    // Foreground poll period, clamped to 2..60 s (Wear polls at 3 s).
    function pollMs() as Number {
        var sec = numberOf("pollSec", 3);
        if (sec < 2) { sec = 2; }
        if (sec > 60) { sec = 60; }
        return sec * 1000;
    }

    // "Basic <base64>" when a username is configured, else null. Matches
    // RadioSettings.authHeader in :core - writes only; reads are public.
    function authHeader() as String? {
        var user = stringOf("authUser", "");
        if (user.length() == 0) {
            return null;
        }
        return "Basic " + StringUtil.encodeBase64(user + ":" + stringOf("authPass", ""));
    }

    // ---- typed property reads with defaults --------------------------------
    // Properties.getValue throws on an unknown key (e.g. a build before the
    // key existed); catch and fall back rather than crash a background run.

    function stringOf(key as String, def as String) as String {
        try {
            var v = Application.Properties.getValue(key);
            if (v instanceof String && v.length() > 0) {
                return v;
            }
        } catch (e) {
        }
        return def;
    }

    function boolOf(key as String, def as Boolean) as Boolean {
        try {
            var v = Application.Properties.getValue(key);
            if (v instanceof Boolean) {
                return v;
            }
        } catch (e) {
        }
        return def;
    }

    function numberOf(key as String, def as Number) as Number {
        try {
            var v = Application.Properties.getValue(key);
            if (v instanceof Number) {
                return v;
            }
            if (v instanceof Float) {
                return v.toNumber();
            }
        } catch (e) {
        }
        return def;
    }

    function trimSlash(url as String) as String {
        var n = url.length();
        while (n > 1 && url.substring(n - 1, n).equals("/")) {
            n--;
            url = url.substring(0, n) as String;
        }
        return url;
    }
}
