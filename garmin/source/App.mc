import Toybox.Application;
import Toybox.Application.Storage;
import Toybox.Background;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.WatchUi;

// Widget + glance + background service. The class is reachable from all three
// scopes, so it carries both annotations. MainView/MainDelegate are foreground-
// only classes referenced from here, which the scope checker would flag; the
// typecheck annotation tells it we know (they're only touched in getInitialView
// and the foreground-only callbacks).
(:background) (:glance) (:typecheck([disableBackgroundCheck, disableGlanceCheck]))
class RadioApp extends Application.AppBase {
    private var _view as MainView?;

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state as Dictionary?) as Void {
    }

    function onStop(state as Dictionary?) as Void {
    }

    // Full widget.
    function getInitialView() as [Views] or [Views, InputDelegates] {
        scheduleBackground();
        var view = new MainView();
        _view = view;
        return [view, new MainDelegate(view)];
    }

    // Glance: draws straight from Storage (on fenix 6 the system re-renders it
    // from a fresh app launch every >= 30 s; requestUpdate is a no-op there).
    function getGlanceView() as [GlanceView] or [GlanceView, GlanceViewDelegate] or Null {
        return [new RadioGlanceView()];
    }

    function getServiceDelegate() as [System.ServiceDelegate] {
        return [new RadioBackgroundService()];
    }

    // Background run finished: persist for the glance, refresh the widget if
    // it happens to be open.
    function onBackgroundData(data as Application.PersistableType) as Void {
        if (data instanceof Array) {
            Storage.setValue(Snap.KEY, data);
            var view = _view;
            if (view != null) {
                view.onSnapshot(data as Model.Snapshot);
            }
        }
    }

    // Settings edited from Garmin Connect while running.
    function onSettingsChanged() as Void {
        scheduleBackground();
        var view = _view;
        if (view != null) {
            view.onSettingsChanged();
        }
    }

    // Register (or cancel) the 5-minute temporal event per the bgRefresh
    // setting. Registering an already-registered interval is harmless.
    private function scheduleBackground() as Void {
        if (Settings.bgRefresh()) {
            Background.registerForTemporalEvent(new Time.Duration(5 * 60));
        } else {
            Background.deleteTemporalEvent();
        }
    }
}
