package io.github.mio.fixtureloader;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import java.io.File;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** OS4 experiment: redirect only fixture getPackageInfo(stock, 0, user=0).
 * Does not mutate PMS cached objects, signatures, grants or app package files.
 * No production UID, source-app LSP scope, Zygote hook, broad hidden-API bypass,
 * service restart or capture is performed here. The enable sentinel must also
 * exist and boot must be complete; all exceptions leave the original result.
 */
public final class FixtureLoaderHook implements IXposedHookLoadPackage {
    static final String STOCK = "com.miui.contentcatcher";
    static final String FIXTURE = "io.github.mio.autopickupfixture";
    static final String CARRIER = "io.github.mio.collectorcarrier";
    static final int FIXTURE_UID = 10483;
    private static final String CERT = "47f1bf86449c4d3021dad441982204c0f8531fdc388e1251ecb31fb9d04a3a4c";
    private static final File ENABLE = new File("/data/system/mio-fixture-loader.enable");
    private static final File WECHAT_ENABLE = new File("/data/system/mio-wechat-loader.enable");
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final AtomicInteger failures = new AtomicInteger();

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (load == null || !"android".equals(load.packageName) || !installed.compareAndSet(false, true)) return;
        try {
            Class<?> owner = Class.forName("com.android.server.pm.IPackageManagerBase", false, load.classLoader);
            Method target = owner.getDeclaredMethod("getPackageInfo", String.class, long.class, int.class);
            if (target.getReturnType() != PackageInfo.class) return;
            Method bootComplete = Class.forName("android.os.SystemProperties").getMethod("getBoolean", String.class, boolean.class);
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    try {
                        // Cheap UID/name checks before file/property access. No
                        // periodic timers, observers or per-app background work.
                        int callingUid = Binder.getCallingUid();
                        if (!FixtureLoaderPolicy.possibleAppQuery(callingUid, call.args) || call.hasThrowable() || failures.get() >= 3) return;
                        if (!(call.getResult() instanceof PackageInfo original) || !STOCK.equals(original.packageName)) return;
                        boolean fixtureMode = FixtureLoaderPolicy.eligible(callingUid, call.args) && ENABLE.isFile();
                        if (!fixtureMode && !WECHAT_ENABLE.isFile()) return;
                        if (!Boolean.TRUE.equals(bootComplete.invoke(null, "sys.boot_completed", false))) return;
                        int user = (Integer) call.args[2];
                        PackageInfo source, carrier;
                        // Query installed identities under the service identity,
                        // restore Binder identity even on errors. No permissions,
                        // signatures, package cache or app APK are changed.
                        long identity = Binder.clearCallingIdentity();
                        try {
                            source = query(target, call.thisObject, fixtureMode ? FIXTURE : "com.tencent.mm", user);
                            carrier = query(target, call.thisObject, CARRIER, user);
                        } finally { Binder.restoreCallingIdentity(identity); }
                        if (source == null || source.applicationInfo == null || source.applicationInfo.uid != callingUid
                                || !(fixtureMode ? trusted(source, FIXTURE) : "com.tencent.mm".equals(source.packageName))
                                || !trusted(carrier, CARRIER)) return;
                        // A fresh result from the original PMS implementation,
                        // not a modified global ApplicationInfo/PackageInfo. The
                        // factory then uses carrier.packageName in createPackageContext.
                        call.setResult(carrier);
                    } catch (Throwable error) {
                        if (failures.incrementAndGet() == 3) safeLog("disabled after three errors: " + error.getClass().getSimpleName());
                    }
                }
            });
            safeLog("installed; separate fixture/WeChat sentinels required after boot; no source-app LSP hooks");
        } catch (Throwable error) { safeLog("unavailable: " + error.getClass().getSimpleName()); }
    }
    private static PackageInfo query(Method method, Object service, String name, int user) throws Throwable {
        Object value = XposedBridge.invokeOriginalMethod(method, service,
                new Object[]{name, (long) PackageManager.GET_SIGNING_CERTIFICATES, user});
        return value instanceof PackageInfo info ? info : null;
    }
    private static boolean trusted(PackageInfo info, String name) throws Exception {
        if (info == null || !name.equals(info.packageName) || info.applicationInfo == null || info.signingInfo == null) return false;
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers == null || signers.length != 1) return false;
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
        StringBuilder hex = new StringBuilder(64);
        for (byte part : hash) { hex.append(Character.forDigit((part >>> 4) & 15, 16)); hex.append(Character.forDigit(part & 15, 16)); }
        return CERT.contentEquals(hex);
    }
    private static void safeLog(String message) {
        try { XposedBridge.log("MioFixtureLoader: " + message); } catch (Throwable ignored) { }
    }
}
