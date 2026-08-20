import Toybox.Background;
import Toybox.Lang;
import Toybox.System;

// Temporal event every 5 min (the platform minimum): fetch, hand the compact
// snapshot to the app via Background.exit -> AppBase.onBackgroundData, which
// writes Storage for the glance. The background slot is 32 KB on fenix 6, so
// only Fetcher/Model/Settings/Api are annotated into it.
(:background)
class RadioBackgroundService extends System.ServiceDelegate {
    private var _fetcher as Fetcher?;

    function initialize() {
        ServiceDelegate.initialize();
    }

    function onTemporalEvent() as Void {
        _fetcher = new Fetcher(method(:onDone));
        (_fetcher as Fetcher).start();
    }

    function onDone(snap as Model.Snapshot) as Void {
        _fetcher = null;
        Background.exit(snap);
    }
}
