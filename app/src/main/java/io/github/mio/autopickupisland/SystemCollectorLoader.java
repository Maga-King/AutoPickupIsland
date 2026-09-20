package io.github.mio.autopickupisland;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** OS4 factory adapter. Default on after boot; no external marker, polling or provider IPC
 * inside package-manager calls. The stock reader still handles normal requests. */
final class SystemCollectorLoader {
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final AtomicInteger errors = new AtomicInteger();
    private static final AtomicBoolean healthBound = new AtomicBoolean();
    private static Method packageQuery;
    private static Method bootComplete;

    static void install(XC_LoadPackage.LoadPackageParam load) {
        if (!"android".equals(load.packageName) || !installed.compareAndSet(false, true)) return;
        XC_MethodHook.Unhook visibility = null, factory = null;
        HookHealth.prepareSystem();
        installHealthAtBoot(load.classLoader);
        try {
            Class<?> owner = Class.forName("com.android.server.pm.IPackageManagerBase", false, load.classLoader);
            packageQuery = owner.getDeclaredMethod("getPackageInfo", String.class, long.class, int.class);
            Method appQuery = owner.getDeclaredMethod("getApplicationInfo", String.class, long.class, int.class);
            if (packageQuery.getReturnType() != PackageInfo.class || appQuery.getReturnType() != ApplicationInfo.class)
                throw new NoSuchMethodException("Package manager ABI");
            bootComplete = Class.forName("android.os.SystemProperties").getMethod("getBoolean", String.class, boolean.class);
            // createPackageContext also queries ApplicationInfo. Only expose this module
            // to a verified allowed source UID, so ordinary APK installation is sufficient.
            visibility = XposedBridge.hookMethod(appQuery, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    try {
                        int uid = Binder.getCallingUid();
                        if (!CollectorLoaderPolicy.moduleInfoQuery(uid, call.args) || call.hasThrowable()
                                || call.getResult() != null || !ready()) return;
                        long identity = Binder.clearCallingIdentity();
                        ApplicationInfo result = null;
                        try {
                            if (authorizedModule(call.thisObject, uid, (Integer) call.args[2]) != null) {
                                Object value = XposedBridge.invokeOriginalMethod(appQuery, call.thisObject, call.args);
                                if (value instanceof ApplicationInfo info && CollectorLoaderPolicy.MODULE.equals(info.packageName)) result = info;
                            }
                        } finally { Binder.restoreCallingIdentity(identity); }
                        if (result != null) {
                            call.setResult(result);
                            HookHealth.state("visibility", "已补齐目标进程对本模块的装载查询");
                        }
                    } catch (Throwable error) { failed(error); }
                }
            });
            factory = XposedBridge.hookMethod(packageQuery, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    try {
                        int uid = Binder.getCallingUid();
                        if (!CollectorLoaderPolicy.factoryQuery(uid, call.args) || call.hasThrowable() || !ready()) return;
                        if (!(call.getResult() instanceof PackageInfo original)
                                || !(CollectorLoaderPolicy.STOCK.equals(original.packageName)
                                || "io.github.mio.collectorcarrier".equals(original.packageName))) return;
                        long identity = Binder.clearCallingIdentity();
                        PackageInfo module;
                        try { module = authorizedModule(call.thisObject, uid, (Integer) call.args[2]); }
                        finally { Binder.restoreCallingIdentity(identity); }
                        if (module != null) {
                            call.setResult(module);
                            HookHealth.state("factory", "系统工厂已指向内置读取器（待读取回执）");
                            logOnce();
                        }
                    } catch (Throwable error) { failed(error); }
                }
            });
            HookHealth.state("loader", "三合一系统装载已安装；启动完成后默认开启");
            log("installed; integrated APK; boot-complete gate; no marker files");
        } catch (Throwable error) {
            try { if (factory != null) factory.unhook(); } catch (Throwable ignored) { }
            try { if (visibility != null) visibility.unhook(); } catch (Throwable ignored) { }
            HookHealth.state("loader", "系统装载不可用：" + error.getClass().getSimpleName());
            log("unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static boolean ready() throws Exception {
        if (errors.get() >= 3 || !Boolean.TRUE.equals(bootComplete.invoke(null, "sys.boot_completed", false))) return false;
        bindHealth(); // One off-thread bind if boot-phase diagnostics were unavailable.
        return true;
    }
    /** Called under cleared Binder identity, invoking the original PMS method only. */
    private static PackageInfo authorizedModule(Object service, int uid, int user) throws Throwable {
        PackageInfo wechat = query(service, CollectorLoaderPolicy.WECHAT, user);
        PackageInfo source = wechat != null && wechat.applicationInfo != null && wechat.applicationInfo.uid == uid
                ? wechat : query(service, CollectorLoaderPolicy.FIXTURE, user);
        if (source == null || source.applicationInfo == null || source.applicationInfo.uid != uid) {
            source = null;
            for (String name : io.github.mio.autopickupisland.coloros.ColorOsCollectorScope.NATIVE_PACKAGES) {
                PackageInfo candidate = query(service, name, user);
                if (candidate != null && candidate.applicationInfo != null && candidate.applicationInfo.uid == uid) {
                    source = candidate; break;
                }
            }
        }
        if (source == null || source.applicationInfo == null || source.applicationInfo.uid != uid) return null;
        PackageInfo module = query(service, CollectorLoaderPolicy.MODULE, user);
        if (module == null || module.applicationInfo == null) return null;
        if (!CollectorLoaderPolicy.moduleMatches(HookEntry.modulePath, module.applicationInfo.sourceDir,
                module.packageName, module.applicationInfo.enabled)) {
            HookHealth.state("loader", "模块已更新或不可用，请重启以装载同版本读取器");
            return null;
        }
        return CollectorLoaderPolicy.sourceAllowed(uid, source.applicationInfo.uid, source.packageName,
                sameSigner(source, module)) ? module : null;
    }
    private static PackageInfo query(Object service, String name, int user) throws Throwable {
        Object value = XposedBridge.invokeOriginalMethod(packageQuery, service,
                new Object[]{name, (long) PackageManager.GET_SIGNING_CERTIFICATES, user});
        return value instanceof PackageInfo info ? info : null;
    }
    private static boolean sameSigner(PackageInfo a, PackageInfo b) {
        if (a.signingInfo == null || b.signingInfo == null) return false;
        Signature[] sa = a.signingInfo.getApkContentsSigners(), sb = b.signingInfo.getApkContentsSigners();
        return sa != null && sb != null && sa.length == 1 && sb.length == 1 && sa[0].equals(sb[0]);
    }
    private static void failed(Throwable error) {
        int count = errors.incrementAndGet();
        if (count <= 3) {
            HookHealth.state("loader_error", "装载异常 " + count + "/3：" + error.getClass().getSimpleName()
                    + (count == 3 ? "；本次启动已停用" : ""));
            log("query error " + count + "/3: " + error.getClass().getSimpleName());
        }
    }
    private static final AtomicBoolean redirected = new AtomicBoolean();
    private static void logOnce() { if (redirected.compareAndSet(false, true)) log("factory redirected to integrated reader"); }

    private static void installHealthAtBoot(ClassLoader loader) {
        try {
            Class<?> owner = Class.forName("com.android.server.SystemServiceManager", false, loader);
            // OS versions have both (int) and (TimingsTraceAndSlog,int) forms.
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (!"startBootPhase".equals(method.getName()) || method.getReturnType() != void.class
                        || params.length < 1 || params.length > 2 || params[params.length - 1] != int.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (!p.hasThrowable() && (Integer) p.args[p.args.length - 1] == 1000) bindHealth();
                            } catch (Throwable ignored) { }
                        }
                    });
            }
        } catch (Throwable error) { log("boot health hook unavailable: " + error.getClass().getSimpleName()); }
    }
    private static void bindHealth() {
        if (!healthBound.compareAndSet(false, true)) return;
        try {
            new Thread(() -> {
                try {
                    Class<?> thread = Class.forName("android.app.ActivityThread");
                    Object instance = XposedHelpers.callStaticMethod(thread, "currentActivityThread");
                    Object value = instance == null ? null : XposedHelpers.callMethod(instance, "getSystemContext");
                    if (value instanceof Context context) HookHealth.attachSystem(context);
                } catch (Throwable ignored) { }
            }, "PickupLoaderHealth").start();
        } catch (Throwable ignored) { }
    }
    private static void log(String message) {
        try { XposedBridge.log("AutoPickupIsland/Loader: " + message); } catch (Throwable ignored) { }
    }
    private SystemCollectorLoader() { }
}
