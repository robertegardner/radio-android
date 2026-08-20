import Toybox.Lang;
import Toybox.WatchUi;

// Buttons on fenix 6 / Mk2 (no touch): UP/DOWN = pages, START = select,
// MENU (hold UP) = refresh, BACK = leave the widget. On the touch Mk3 the
// same behaviors arrive as swipes/taps via BehaviorDelegate.
class MainDelegate extends WatchUi.BehaviorDelegate {
    private var _view as MainView;

    function initialize(view as MainView) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onNextPage() as Boolean {
        _view.nextPage();
        return true;
    }

    function onPreviousPage() as Boolean {
        _view.prevPage();
        return true;
    }

    function onSelect() as Boolean {
        _view.select();
        return true;
    }

    function onMenu() as Boolean {
        _view.refresh();
        return true;
    }
}
