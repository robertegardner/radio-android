import Toybox.Application.Storage;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.WatchUi;

// Palette + text fitting shared by the glance and the widget. Amber-on-black
// like the phone/Wear apps. Every colour here is in the 64-colour MIP palette
// (channels are multiples of 0x55) so fenix 6 / Mk2 render them exactly; the
// Mk3 AMOLED shows the same values on true black.
(:glance)
module Ui {
    const AMBER   = 0xFFAA00;
    const WHITE   = 0xFFFFFF;
    const DIM     = 0xAAAAAA;
    const GREEN   = 0x55FF55;
    const RED     = 0xFF5555;
    const BLACK   = 0x000000;

    // [header, big, line, status] fonts per screen class. The layouts carry
    // positions per resolution; fonts live here because WatchUi.Text has no
    // font getter and we need the font to measure/truncate.
    function fonts(dc as Dc) as Array<FontDefinition> {
        var w = dc.getWidth();
        if (w >= 454) {
            return [Graphics.FONT_TINY, Graphics.FONT_LARGE, Graphics.FONT_SMALL, Graphics.FONT_TINY];
        }
        if (w >= 390) {
            return [Graphics.FONT_TINY, Graphics.FONT_MEDIUM, Graphics.FONT_SMALL, Graphics.FONT_TINY];
        }
        if (w >= 280) {
            return [Graphics.FONT_XTINY, Graphics.FONT_MEDIUM, Graphics.FONT_TINY, Graphics.FONT_XTINY];
        }
        return [Graphics.FONT_XTINY, Graphics.FONT_SMALL, Graphics.FONT_TINY, Graphics.FONT_XTINY];
    }

    function stalenessColor(level as Number) as Number {
        if (level == 0) { return DIM; }
        return level == 1 ? AMBER : RED;
    }

    // Truncate with "..." to fit maxW pixels (no wrapping on these screens).
    function fit(dc as Dc, text as String, font as FontType, maxW as Number) as String {
        if (dc.getTextWidthInPixels(text, font) <= maxW) {
            return text;
        }
        var n = text.length();
        while (n > 1) {
            n = n - 1;
            var t = text.substring(0, n) + "...";
            if (dc.getTextWidthInPixels(t, font) <= maxW) {
                return t;
            }
        }
        return "...";
    }

    // Usable chord width of a round screen at row y, minus a margin.
    function chord(dc as Dc, y as Number) as Number {
        var r = dc.getWidth() / 2;
        var dy = y - dc.getHeight() / 2;
        var sq = r * r - dy * dy;
        if (sq <= 0) {
            return r;
        }
        var half = Math.sqrt(sq).toNumber();
        var w = 2 * half - 16;
        return w < r ? r : w;
    }
}

// The glance: "RG2 RADIO    3m" over the headline (artist — title, or
// station · freq). Reads only Storage - no requests, no timers - so it fits
// the 32 KB glance slot and survives the background-render lifecycle.
(:glance)
class RadioGlanceView extends WatchUi.GlanceView {

    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc as Dc) as Void {
        var raw = Storage.getValue(Snap.KEY);
        var snap = (raw instanceof Array) ? raw as Model.Snapshot : null;

        var w = dc.getWidth();
        var h = dc.getHeight();
        var fontTop = Graphics.FONT_XTINY;
        var fontMain = Graphics.FONT_TINY;
        var topH = dc.getFontHeight(fontTop);
        var mainH = dc.getFontHeight(fontMain);
        var y0 = (h - topH - mainH) / 2;
        if (y0 < 0) { y0 = 0; }

        dc.setColor(Ui.BLACK, Ui.BLACK);
        dc.clear();

        // Header + staleness.
        var age = Model.ageText(snap);
        var level = Model.staleness(snap);
        dc.setColor(Ui.AMBER, Graphics.COLOR_TRANSPARENT);
        dc.drawText(0, y0, fontTop, "RG2 RADIO", Graphics.TEXT_JUSTIFY_LEFT);
        dc.setColor(Ui.stalenessColor(level), Graphics.COLOR_TRANSPARENT);
        dc.drawText(w, y0, fontTop, age, Graphics.TEXT_JUSTIFY_RIGHT);

        // Headline (or the reason there isn't one).
        var line = Model.headline(snap);
        if (snap != null && (snap[Snap.RCODE] as Number) != 200 && (snap[Snap.TITLE] == null)) {
            line = Model.errorText(snap[Snap.RCODE] as Number);
        }
        dc.setColor(Ui.WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(0, y0 + topH, fontMain, Ui.fit(dc, line, fontMain, w), Graphics.TEXT_JUSTIFY_LEFT);
    }
}
