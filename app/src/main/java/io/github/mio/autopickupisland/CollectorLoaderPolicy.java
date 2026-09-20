package io.github.mio.autopickupisland;

/** Pure query boundaries; installed package identities are additionally checked by the loader. */
final class CollectorLoaderPolicy {
    static final String MODULE = "io.github.mio.autopickupisland";
    static final String STOCK = "com.miui.contentcatcher";
    static final String WECHAT = "com.tencent.mm";
    static final String FIXTURE = "io.github.mio.autopickupfixture";
    private CollectorLoaderPolicy() { }

    static boolean sameUserApp(int uid, Object[] args) {
        return uid >= 10_000 && uid % 100_000 >= 10_000 && uid % 100_000 < 20_000
                && args != null && args.length == 3 && args[0] instanceof String
                && args[1] instanceof Long flags && flags >= 0
                && args[2] instanceof Integer user && user >= 0 && user == uid / 100_000;
    }
    static boolean factoryQuery(int uid, Object[] args) {
        return sameUserApp(uid, args) && STOCK.equals(args[0]) && (Long) args[1] == 0L;
    }
    static boolean moduleInfoQuery(int uid, Object[] args) {
        return sameUserApp(uid, args) && MODULE.equals(args[0]);
    }
    static boolean sourceAllowed(int callingUid, int installedUid, String packageName, boolean sameSigner) {
        return callingUid == installedUid
                && (WECHAT.equals(packageName)
                    || io.github.mio.autopickupisland.coloros.ColorOsCollectorScope.NATIVE_PACKAGES.contains(packageName == null ? "" : packageName)
                    || (FIXTURE.equals(packageName) && sameSigner));
    }
    static boolean moduleMatches(String loadedPath, String installedPath, String packageName, boolean enabled) {
        return enabled && MODULE.equals(packageName) && loadedPath != null && !loadedPath.isEmpty()
                && loadedPath.equals(installedPath);
    }
}
