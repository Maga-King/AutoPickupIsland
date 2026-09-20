package io.github.mio.fixtureloader;

/** No Android/Xposed dependency: exhaustively checked before system-server use. */
public final class FixtureLoaderPolicy {
    private FixtureLoaderPolicy() { }
    public static boolean eligible(int uid, Object[] args) {
        return uid == 10483 && args != null && args.length == 3 && "com.miui.contentcatcher".equals(args[0])
                && args[1] instanceof Long flags && flags == 0L
                && args[2] instanceof Integer user && user == 0;
    }
    public static boolean possibleAppQuery(int uid, Object[] args) {
        return uid >= 10_000 && uid % 100_000 >= 10_000 && uid % 100_000 < 20_000 && args != null && args.length == 3
                && "com.miui.contentcatcher".equals(args[0]) && args[1] instanceof Long flags && flags == 0L
                && args[2] instanceof Integer user && user >= 0 && user == uid / 100_000;
    }
}
