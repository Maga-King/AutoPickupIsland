package io.github.mio.autopickupisland;

import android.app.Application;
import android.content.Context;
import android.os.*;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.concurrent.*;

/** Event-driven diagnostics only. Reports are last observations, not liveness probes. */
final class HookHealth {
    private static final ConcurrentHashMap<String,String> states = new ConcurrentHashMap<>();
    private static volatile Context context;
    private static volatile String role;
    private static long lastCallback;
    private static final java.util.concurrent.atomic.AtomicBoolean sending = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
    static void prepareSystem() { role = "loader"; }
    static void attachSystem(Context value) {
        try { if ("loader".equals(role)) { context = value; flush(); } } catch (Throwable ignored) { }
    }
    static void start(XC_LoadPackage.LoadPackageParam p) {
        if (p.processName.equals(Constants.PKG_VOICE_ASSIST)) role = "voice";
        else if (p.processName.equals(Constants.PKG_AICR + ":cognitionService")) role = "aicr";
        else if (p.processName.equals(Constants.PKG_PRIVILEGED_ASSIST)) role = "assist";
        else return;
        state("entry", "已进入模块入口；尚未验证具体 Hook");
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try { context = (Context) param.args[0]; flush(); } catch (Throwable ignored) { }
            }
        });
    }
    static void state(String key, String value) {
        try {
            if (role == null || key == null) return;
            String clean = PickupEvent.truncate(PickupEvent.clean(value), 200);
            if (!clean.equals(states.put(key, clean))) { revision.incrementAndGet(); flush(); }
        } catch (Throwable ignored) { /* Diagnostics must not break the host. */ }
    }
    static synchronized void callback(String value) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCallback < 30_000 && lastCallback != 0) return;
        lastCallback = now;
        state("callback", value + " @ " + System.currentTimeMillis());
    }
    private static void flush() {
        Context c = context;
        if (c == null || !sending.compareAndSet(false, true)) return;
        try {
        boolean queued = new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                new Thread(() -> {
                    long observedRevision = revision.get();
                    try {
                        org.json.JSONObject data = new org.json.JSONObject(states);
                        data.put("pid", android.os.Process.myPid());
                        data.put("version", c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode());
                        data.put("module_path", HookEntry.modulePath);
                        data.put("boot", android.provider.Settings.Global.getInt(c.getContentResolver(), "boot_count", -1));
                        data.put("at", System.currentTimeMillis());
                        Bundle b = new Bundle(); b.putString("role", role); b.putString("data", data.toString());
                        c.getContentResolver().call(Constants.PROVIDER_URI, "hook_health", null, b);
                    } catch (Throwable ignored) { }
                    finally {
                        sending.set(false);
                        // A changed event during IPC gets one coalesced follow-up, not a poll.
                        if (revision.get() != observedRevision) flush();
                    }
                }, "PickupHookHealth").start();
            } catch (Throwable ignored) { sending.set(false); }
        }, 300);
        if (!queued) sending.set(false);
        } catch (Throwable ignored) { sending.set(false); }
    }
}
