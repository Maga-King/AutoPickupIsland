package io.github.mio.autopickupisland;

import android.service.notification.StatusBarNotification;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Hide only OS4's backing view, never the row/content, and never mutate a shared drawable. */
final class SystemUiNotificationBackgroundHook {
    private static final String ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    private static final String BACKGROUND = "com.android.systemui.statusbar.notification.row.NotificationBackgroundView";
    private static final AtomicInteger FAILURES = new AtomicInteger();
    private static final Map<View, Boolean> HIDDEN = Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean logged;

    static void install(ClassLoader loader) {
        try {
            Class<?> row = Class.forName(ROW, false, loader);
            Method update = SafeReflection.method(row, "updateBackgroundForGroupState", void.class);
            Method rebound = SafeReflection.method(row, "onNotificationUpdated", void.class);
            Field background = null;
            for (Class<?> current = row; current != null; current = current.getSuperclass()) {
                try { background = current.getDeclaredField("mBackgroundNormal"); break; }
                catch (NoSuchFieldException ignored) { }
            }
            if (update == null || background == null || !BACKGROUND.equals(background.getType().getName())) {
                log("OS4 background ABI unavailable; skipped");
                return;
            }
            background.setAccessible(true);
            Field backing = background;
            XC_MethodHook callback = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (FAILURES.get() >= 4 || param.hasThrowable()) return;
                    try {
                        if (!(param.thisObject instanceof View view)) return;
                        Object adapter = SafeReflection.noArg(view, "getEntryAdapter");
                        Object value = SafeReflection.noArg(adapter, "getSbn");
                        if (!(value instanceof StatusBarNotification sbn)) return;
                        if (!PickupNotificationScope.owns(sbn.getPackageName(), sbn.getNotification())) {
                            // A row can be rebound to a regular notification. Let OEM recompute it.
                            if (HIDDEN.remove(view) != null && param.method != update) update.invoke(view);
                            return;
                        }
                        Object candidate = backing.get(view);
                        if (!(candidate instanceof View backdrop)) return;
                        HIDDEN.put(view, true);
                        backdrop.setVisibility(View.INVISIBLE);
                        if (!logged) { logged = true; log("hid only marked pickup notification backing view"); }
                    } catch (Throwable error) {
                        if (FAILURES.incrementAndGet() == 4) log("background guard disabled after repeated failures");
                    }
                }
            };
            XposedBridge.hookMethod(update, callback);
            if (rebound != null) XposedBridge.hookMethod(rebound, callback);
            log("OS4 per-notification background hook installed");
        } catch (Throwable ignored) { log("background hook unavailable; preserving OEM renderer"); }
    }
    private static void log(String message) {
        try { XposedBridge.log("AutoPickupIsland/SystemUI: " + message); } catch (Throwable ignored) { }
    }
}
