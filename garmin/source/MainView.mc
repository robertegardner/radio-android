import Toybox.Application.Storage;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

// The widget. Four pages (up/down or swipe), mirroring the Wear app's one
// scrolling column top to bottom:
//   0 NOW PLAYING  - station · freq, artist, title/caption, stereo + antenna
//   1 RADIO        - favorites; SELECT opens the list, picking one tunes
//   2 MOSWIN       - scanner job/talkgroup; SELECT switches the SDR to MOSWIN
//   3 AVIATION     - presets; SELECT opens the list, picking one tunes
// Pages 2-3 disappear when the showScanner setting is off.
// MENU = refresh now. Polls while open at the configured interval.
class MainView extends WatchUi.View {

    const PAGE_NOW = 0;
    const PAGE_RADIO = 1;
    const PAGE_MOSWIN = 2;
    const PAGE_AVIATION = 3;

    private var _page as Number = PAGE_NOW;
    private var _snap as Model.Snapshot?;
    private var _fetcher as Fetcher?;
    private var _timer as Timer.Timer?;
    private var _busy as Boolean = false;
    private var _amoled as Boolean = false;
    private var _msg as String?;
    private var _msgUntil as Number = 0;

    function initialize() {
        View.initialize();
        var raw = Storage.getValue(Snap.KEY);
        if (raw instanceof Array) {
            _snap = raw as Model.Snapshot;
        }
    }

    function onLayout(dc as Dc) as Void {
        setLayout(Rez.Layouts.MainLayout(dc));
        // Mk3 (390/454) is AMOLED; everything else in the target set is MIP.
        _amoled = dc.getWidth() >= 390;
    }

    function onShow() as Void {
        refresh();
        startTimer();
    }

    function onHide() as Void {
        var t = _timer;
        if (t != null) {
            t.stop();
        }
        _timer = null;
    }

    // ---- data --------------------------------------------------------------

    function refresh() as Void {
        if (_busy) {
            return;
        }
        _busy = true;
        var f = new Fetcher(method(:onSnapshot));
        _fetcher = f;
        f.start();
        WatchUi.requestUpdate();
    }

    function onSnapshot(snap as Model.Snapshot) as Void {
        _busy = false;
        _fetcher = null;
        _snap = snap;
        Storage.setValue(Snap.KEY, snap);
        WatchUi.requestUpdate();
    }

    function onTick() as Void {
        refresh();
    }

    function onSettingsChanged() as Void {
        if (!Settings.showScanner() && _page > PAGE_RADIO) {
            _page = PAGE_RADIO;
        }
        startTimer();
        WatchUi.requestUpdate();
    }

    private function startTimer() as Void {
        var t = _timer;
        if (t != null) {
            t.stop();
        } else {
            t = new Timer.Timer();
            _timer = t;
        }
        t.start(method(:onTick), Settings.pollMs(), true);
    }

    // ---- navigation / actions (called by MainDelegate) ---------------------

    function pageCount() as Number {
        return Settings.showScanner() ? 4 : 2;
    }

    function nextPage() as Void {
        _page = (_page + 1) % pageCount();
        WatchUi.requestUpdate();
    }

    function prevPage() as Void {
        _page = (_page + pageCount() - 1) % pageCount();
        WatchUi.requestUpdate();
    }

    function select() as Void {
        if (_page == PAGE_NOW) {
            refresh();
        } else if (_page == PAGE_RADIO) {
            SourceMenu.pushFavorites(self);
        } else if (_page == PAGE_MOSWIN) {
            showMessage("Switching to MOSWIN...", 25);
            Control.selectMoswin(method(:onControl));
        } else if (_page == PAGE_AVIATION) {
            SourceMenu.pushAviation(self);
        }
    }

    // Called by the menus after the POST is issued.
    function showMessage(text as String, seconds as Number) as Void {
        _msg = text;
        _msgUntil = Time.now().value() + seconds;
        WatchUi.requestUpdate();
    }

    // Shared response handler for every write endpoint. Both backends use
    // JSON envelopes: radio {ok,error}; scanner {} or {error}. 400 bodies are
    // JSON too, so surface their message instead of the bare status.
    function onControl(code as Number, data as Dictionary or String or Null) as Void {
        var text as String;
        if (data instanceof Dictionary && data["error"] != null) {
            text = "Rejected: " + data["error"];
        } else if (code == 200) {
            text = (data instanceof Dictionary && data["ok"] == false) ? "Rejected" : "Sent OK";
        } else {
            text = Model.errorText(code);
        }
        data = null;
        showMessage(text, 6);
        // The backend restarts the stream/SDR; a poll shortly after shows it.
        refresh();
    }

    // ---- drawing -----------------------------------------------------------

    function onUpdate(dc as Dc) as Void {
        var dim = _amoled ? 0x888888 : Ui.DIM;   // 0x888888 isn't in the MIP palette
        var snap = _snap;
        var title = "";
        var l1 as String? = null; var l2 as String? = null;
        var l3 as String? = null; var l4 as String? = null;
        var c1 as Number = Ui.WHITE; var c2 as Number = Ui.WHITE;
        var c3 as Number = Ui.WHITE; var c4 as Number = dim;

        if (_page == PAGE_NOW) {
            title = "RG2 RADIO";
            if (snap == null) {
                l1 = _busy ? "Loading..." : "No data";
            } else if ((snap[Snap.RCODE] as Number) != 200 && snap[Snap.FREQ] == null) {
                l1 = Model.errorText(snap[Snap.RCODE] as Number);
                c1 = Ui.RED;
                l2 = "Check server URL";
                c2 = dim;
            } else {
                l1 = Model.stationLine(snap);
                if (l1 == null) { l1 = "Nothing tuned"; c1 = dim; }
                var mode = snap[Snap.MODE] as String?;
                l2 = snap[Snap.ARTIST] as String?;
                l3 = snap[Snap.TITLE] as String?;
                if (l3 == null && mode != null && mode.equals("captions")) {
                    // Cardinals play-by-play: live Whisper caption instead of a song.
                    l2 = "LIVE CAPTION";
                    c2 = Ui.AMBER;
                    l3 = snap[Snap.CAPTION] as String?;
                } else if (l3 == null) {
                    l2 = null;
                    l3 = "no track ID";
                    c3 = dim;
                }
                var bits = (snap[Snap.PILOT] == true) ? "STEREO" : "MONO";
                if (snap[Snap.PILOT] == true) { c4 = Ui.GREEN; }
                var ant = snap[Snap.ANT] as String?;
                if (ant != null) { bits = bits + " · " + Catalog.shortAntenna(ant); }
                l4 = bits;
            }
        } else if (_page == PAGE_RADIO) {
            title = "RADIO";
            var favs = Catalog.favorites();
            var lines = [null, null, null, null] as Array<String?>;
            for (var i = 0; i < favs.size() && i < 4; i++) {
                var f = favs[i];
                var mark = isTuned(snap, f[0], f[1]) ? "> " : "";
                lines[i] = mark + f[2];
            }
            l1 = lines[0]; l2 = lines[1]; l3 = lines[2]; l4 = lines[3];
            c4 = Ui.WHITE;
        } else if (_page == PAGE_MOSWIN) {
            title = "MOSWIN";
            var job = scannerJob(snap);
            var detail = (snap != null) ? snap[Snap.SDETAIL] as String? : null;
            if (job == null) {
                l1 = (snap != null && (snap[Snap.SCODE] as Number) != 200)
                    ? Model.errorText(snap[Snap.SCODE] as Number) : "Scanner idle";
                c1 = Ui.RED;
            } else if (job.equals(Catalog.JOB_MOSWIN)) {
                l1 = "P25 ACTIVE";
                c1 = Ui.GREEN;
                l2 = talkgroup(detail);
                if (l2 == null) { l2 = "control channel"; c2 = dim; }
            } else {
                l1 = "SDR: " + job;
                c1 = Ui.AMBER;
                l2 = detail;
                c2 = dim;
            }
            l3 = "SELECT: switch to MOSWIN";
            c3 = dim;
        } else {
            title = "AVIATION";
            var job = scannerJob(snap);
            var detail = (snap != null) ? snap[Snap.SDETAIL] as String? : null;
            if (job != null && job.equals(Catalog.JOB_MONITOR)) {
                l1 = "MONITOR ON";
                c1 = Ui.GREEN;
                l2 = detail;
            } else {
                l1 = "Monitor off";
                c1 = dim;
            }
            l3 = "SELECT: pick a channel";
            c3 = dim;
            l4 = "6 Cape air-band presets";
        }

        // Status line: transient action message beats staleness + errors.
        var status as String;
        var sc = Ui.stalenessColor(Model.staleness(snap));
        if (_msg != null && Time.now().value() < _msgUntil) {
            status = _msg;
            sc = Ui.AMBER;
        } else if (_busy) {
            status = "Updating...";
        } else {
            status = "updated " + Model.ageText(snap);
            if (snap != null && (snap[Snap.RCODE] as Number) != 200) {
                status = Model.errorText(snap[Snap.RCODE] as Number);
                sc = Ui.RED;
            }
        }

        var fonts = Ui.fonts(dc);
        setLabel(dc, "hdr", title, Ui.AMBER, fonts[0]);
        setLabel(dc, "l1", l1, c1, fonts[1]);
        setLabel(dc, "l2", l2, c2, fonts[2]);
        setLabel(dc, "l3", l3, c3, fonts[2]);
        setLabel(dc, "l4", l4, c4, fonts[2]);
        setLabel(dc, "status", status, sc, fonts[3]);

        dc.setColor(Ui.WHITE, Ui.BLACK);
        dc.clear();
        View.onUpdate(dc);
    }

    private function setLabel(dc as Dc, id as String, text as String?, color as Number, font as FontDefinition) as Void {
        var lbl = findDrawableById(id);
        if (lbl instanceof WatchUi.Text) {
            var t = (text == null) ? "" : text;
            var y = lbl.locY.toNumber() + dc.getFontHeight(font) / 2;
            lbl.setFont(font);
            lbl.setText(Ui.fit(dc, t, font, Ui.chord(dc, y)));
            lbl.setColor(color);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private function isTuned(snap as Model.Snapshot?, band as String, freq as String) as Boolean {
        if (snap == null) {
            return false;
        }
        var b = snap[Snap.BAND] as String?;
        var f = snap[Snap.FREQ] as String?;
        return b != null && f != null && b.equals(band) && f.equals(freq);
    }

    private function scannerJob(snap as Model.Snapshot?) as String? {
        return (snap == null) ? null : snap[Snap.SJOB] as String?;
    }

    // "active: <TG>" -> "<TG>", else null (same rule as ScannerJob.activeTalkgroup).
    private function talkgroup(detail as String?) as String? {
        if (detail != null && detail.length() > 7 && detail.substring(0, 7).equals("active:")) {
            var tg = detail.substring(7, detail.length()) as String;
            while (tg.length() > 0 && tg.substring(0, 1).equals(" ")) {
                tg = tg.substring(1, tg.length()) as String;
            }
            return tg.length() > 0 ? tg : null;
        }
        return null;
    }
}
