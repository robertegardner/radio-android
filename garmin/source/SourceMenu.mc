import Toybox.Lang;
import Toybox.WatchUi;

// Native Menu2 lists for the two "pick one" pages. Menu2 is system-drawn, so
// it costs us only the item strings, and it already handles round screens,
// scrolling and touch vs buttons on every target.
module SourceMenu {

    function pushFavorites(view as MainView) as Void {
        var menu = new WatchUi.Menu2({ :title => "Radio" });
        var favs = Catalog.favorites();
        for (var i = 0; i < favs.size(); i++) {
            menu.addItem(new WatchUi.MenuItem(favs[i][2], favs[i][3], i, null));
        }
        WatchUi.pushView(menu, new FavoritesMenuDelegate(view), WatchUi.SLIDE_UP);
    }

    function pushAviation(view as MainView) as Void {
        var menu = new WatchUi.Menu2({ :title => "Aviation" });
        var presets = Catalog.aviationPresets();
        for (var i = 0; i < presets.size(); i++) {
            menu.addItem(new WatchUi.MenuItem(presets[i][1], presets[i][2], i, null));
        }
        WatchUi.pushView(menu, new AviationMenuDelegate(view), WatchUi.SLIDE_UP);
    }
}

class FavoritesMenuDelegate extends WatchUi.Menu2InputDelegate {
    private var _view as MainView;

    function initialize(view as MainView) {
        Menu2InputDelegate.initialize();
        _view = view;
    }

    function onSelect(item as MenuItem) as Void {
        var id = item.getId();
        if (id instanceof Number) {
            var f = Catalog.favorites()[id];
            _view.showMessage("Tuning " + f[2] + "...", 20);
            Control.tune(f[0], f[1], _view.method(:onControl));
        }
        WatchUi.popView(WatchUi.SLIDE_DOWN);
    }

    function onBack() as Void {
        WatchUi.popView(WatchUi.SLIDE_DOWN);
    }
}

class AviationMenuDelegate extends WatchUi.Menu2InputDelegate {
    private var _view as MainView;

    function initialize(view as MainView) {
        Menu2InputDelegate.initialize();
        _view = view;
    }

    function onSelect(item as MenuItem) as Void {
        var id = item.getId();
        if (id instanceof Number) {
            var p = Catalog.aviationPresets()[id];
            _view.showMessage("Tuning " + p[1] + "...", 25);
            Control.tuneMonitor(p[0], p[1], _view.method(:onControl));
        }
        WatchUi.popView(WatchUi.SLIDE_DOWN);
    }

    function onBack() as Void {
        WatchUi.popView(WatchUi.SLIDE_DOWN);
    }
}
