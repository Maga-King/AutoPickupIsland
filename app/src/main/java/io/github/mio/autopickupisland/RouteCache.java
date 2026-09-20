package io.github.mio.autopickupisland;

final class RouteCache {
    private static volatile PickupEvent navigation;
    private static volatile PickupEvent recognized;

    private RouteCache() { }
    static void clear() { navigation = null; recognized = null; }

    static void update(PickupEvent event) {
        if (event == null) return;
        if (event.isMiniProgram() && (!event.pathObserved || event.route.isEmpty())) return;
        if (event.navigationOnly) navigation = event;
        else recognized = event;
    }

    static PickupEvent best() {
        PickupEvent nav = navigation;
        PickupEvent event = recognized;
        if (nav != null && !nav.isFresh(Constants.NAV_MAX_AGE_MS)) nav = null;
        if (event != null && !event.isFresh(Constants.NAV_MAX_AGE_MS)) event = null;
        if (nav == null) return event;
        if (event == null) return nav;
        return nav.timestamp >= event.timestamp ? nav : event;
    }
}
