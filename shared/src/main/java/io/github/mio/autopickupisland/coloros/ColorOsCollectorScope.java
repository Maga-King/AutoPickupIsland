package io.github.mio.autopickupisland.coloros;

/** Host safety gate, not ColorOS classification rules. Loading the carrier into
 * a host does NOT authorize reading every Activity in that host. No chat UI.
 */
public final class ColorOsCollectorScope {
    public static final String FIXTURE = "io.github.mio.autopickupfixture";
    public static final String WECHAT = "com.tencent.mm";
    // Native package envelope from verified OEM XML 20260702. Activity matching
    // and ignoreVis still come from the active RuleRepository, not this list.
    public static final java.util.List<String> NATIVE_PACKAGES = java.util.List.of(
            "com.lucky.luckyclient", "com.mxbc.mxsa", "com.yek.android.kfc.activitys",
            "com.mcdonalds.gma.cn", "com.starbucks.cn");
    private ColorOsCollectorScope() { }
    public static boolean packageAllowed(String name) {
        return nativePackageAllowed(name) || WECHAT.equals(name);
    }
    public static boolean nativePackageAllowed(String name) {
        return FIXTURE.equals(name) || name != null && NATIVE_PACKAGES.contains(name);
    }
    public static boolean activityAllowed(String name, String activity) {
        if (activity == null) return false;
        if (FIXTURE.equals(name)) return activity.startsWith(FIXTURE + ".");
        return WECHAT.equals(name) && activity.startsWith("com.tencent.mm.plugin.appbrand.ui.AppBrandUI")
                && activity.substring("com.tencent.mm.plugin.appbrand.ui.AppBrandUI".length()).matches("[0-9]*");
    }
}
