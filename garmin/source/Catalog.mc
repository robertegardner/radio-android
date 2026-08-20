import Toybox.Lang;

// Static source catalogs, mirroring core Favorites.SEED and
// ScannerCatalog.PRESETS. Returned as fresh arrays (not module vars) so they
// only occupy memory while a menu is open.
module Catalog {

    // [band, freq-as-sent-to-/api/tune, label, sublabel]
    function favorites() as Array<Array<String>> {
        return [
            ["am", "1120",  "KMOX 1120",  "St. Louis · Cardinals flagship"],
            ["am", "1230",  "KZYM 1230",  "Cape Girardeau · Cardinals affiliate"],
            ["fm", "100.7", "KGMO 100.7", "Cape Girardeau · local FM"],
            ["fm", "95.7",  "95.7 FM",    "Cardinals affiliate"]
        ];
    }

    // [freq with unit suffix (backend parses k/M/G), label, description]
    function aviationPresets() as Array<Array<String>> {
        return [
            ["132.536M", "Memphis Ctr", "132.536 · AM"],
            ["125.525M", "KCGI Tower",  "125.525 · AM"],
            ["124.710M", "Approach",    "124.710 · AM"],
            ["135.500M", "Center",      "135.500 · AM"],
            ["127.490M", "ARTCC",       "127.490 · AM"],
            ["128.320M", "ARTCC 2",     "128.320 · AM"]
        ];
    }

    const JOB_MOSWIN = "ems_scanner";
    const JOB_MONITOR = "monitor";

    // "Antenna B" -> "ANT B", "HF+" -> "HF+"
    function shortAntenna(a as String) as String {
        if (a.length() > 8 && a.substring(0, 8).equals("Antenna ")) {
            return "ANT " + a.substring(8, a.length());
        }
        return a;
    }
}
