package io.github.mio.autopickupisland;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.view.View;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** OS4 plugin is dynamically loaded. Discover its actual class on attachment, not by loading DEX. */
final class SystemUiPickupGuard {
    private static final String CONTENT = "miui.systemui.dynamicisland.window.content.DynamicIslandContentView";
    private static final Set<Class<?>> TRIED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final AtomicInteger FAILURES = new AtomicInteger();
    static void observe(View view) {
        if (view == null || FAILURES.get() >= 4 || !CONTENT.equals(view.getClass().getName())) return;
        Class<?> type = view.getClass();
        if (TRIED.size() >= 4 || !TRIED.add(type)) return;
        try {
            Method canSlide = SafeReflection.method(type, "canExpandedViewSlide", boolean.class, type);
            if (canSlide == null) { log("OS4 mini-window predicate unavailable; leaving OEM behavior unchanged"); return; }
            XposedBridge.hookMethod(canSlide, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (FAILURES.get() >= 4) return;
                    try {
                        if (param.args == null || param.args.length != 1 || param.args[0] == null) return;
                        Object data = SafeReflection.noArg(param.args[0], "getCurrentIslandData");
                        Object value = SafeReflection.noArg(data, "getExtras");
                        if (!(value instanceof Bundle extras)) return;
                        StatusBarNotification sbn = extras.getParcelable("miui.sbn", StatusBarNotification.class);
                        if (sbn != null && isOurCard(sbn.getPackageName(), sbn.getNotification())) param.setResult(false);
                    } catch (Throwable error) { fail(); }
                }
            });
            log("OS4 mini-window drag disabled only for marked pickup cards");
        } catch (Throwable error) { fail(); }
    }
    static boolean isOurCard(String pkg, Notification notification) {
        return PickupNotificationScope.owns(pkg, notification);
    }
    private static void fail() {
        if (FAILURES.incrementAndGet() == 4) log("mini-window guard circuit opened; preserving OEM behavior");
    }
    private static void log(String message) {
        try { XposedBridge.log("AutoPickupIsland/SystemUI: " + message); } catch (Throwable ignored) { }
    }
}
