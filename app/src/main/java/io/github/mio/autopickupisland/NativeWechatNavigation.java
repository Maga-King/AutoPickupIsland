package io.github.mio.autopickupisland;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.widget.Toast;
import dalvik.system.DexFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.UUID;
import de.robv.android.xposed.XposedBridge;

/** Calls Xiaomi's existing WeChat OpenSDK bridge without AICR's notification-database gate.
 * Private random capabilities authenticate notification taps, including after process death.
 * Never accepts a caller-supplied appId/path; no source-app hooks or arbitrary Intent execution. */
final class NativeWechatNavigation {
    static final String ACTION = Constants.MODULE_PACKAGE + ".OPEN_WECHAT_PICKUP_V2";
    private static volatile Method voiceMethod;
    private static long lastTap;

    static PendingIntent create(Context context, PickupEvent event) throws Exception {
        String owner = context.getPackageName();
        if (!Constants.PKG_AICR.equals(owner) && !Constants.PKG_VOICE_ASSIST.equals(owner)) {
            throw new SecurityException("Unexpected navigation owner");
        }
        if (!event.appId.matches("gh_[A-Za-z0-9_]{1,96}")) {
            throw new IllegalArgumentException("WeChat OpenSDK requires the mini-program original ID");
        }
        String path = event.openPath();
        if (path.length() > 2048 || path.indexOf('\u0000') >= 0) throw new IllegalArgumentException("Invalid path");
        String capability = UUID.randomUUID().toString().replace("-", "");
        File folder = new File(context.getFilesDir(), "mio_pickup_navigation_v2");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("Navigation storage unavailable");
        File[] records = folder.listFiles(file -> file.getName().matches("[a-f0-9]{32}\\.json"));
        if (records != null) {
            Arrays.sort(records, Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < records.length; i++) {
                if (i < records.length - 31 || System.currentTimeMillis() - records[i].lastModified()
                        > Constants.NAV_MAX_AGE_MS) new AtomicFile(records[i]).delete();
            }
        }
        JSONObject data = new JSONObject().put("appId", event.appId).put("path", path)
                .put("expires", System.currentTimeMillis() + Constants.NAV_MAX_AGE_MS);
        AtomicFile record = new AtomicFile(new File(folder, capability + ".json"));
        FileOutputStream output = null;
        try {
            output = record.startWrite();
            output.write(data.toString().getBytes(StandardCharsets.UTF_8));
            record.finishWrite(output);
        } catch (Exception error) { record.failWrite(output); throw error; }
        String receiver = Constants.PKG_AICR.equals(owner)
                ? Constants.AICR_WAKE_RECEIVER : Constants.VOICE_ASSIST_WAKE_RECEIVER;
        Intent intent = new Intent(ACTION).setComponent(new ComponentName(owner, receiver))
                .setIdentifier("mio-wechat-" + capability).putExtra("capability", capability)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        return PendingIntent.getBroadcast(context, capability.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static boolean handle(Context context, Intent intent, ClassLoader loader) {
        if (!ACTION.equals(intent.getAction())) return false;
        try {
            String capability = intent.getStringExtra("capability");
            if (capability == null || !capability.matches("[a-f0-9]{32}")) return true;
            File record = new File(new File(context.getFilesDir(), "mio_pickup_navigation_v2"), capability + ".json");
            if (!record.isFile() || record.length() > 8192) return true;
            JSONObject data = new JSONObject(new String(new AtomicFile(record).readFully(), StandardCharsets.UTF_8));
            long remaining = data.optLong("expires") - System.currentTimeMillis();
            if (remaining <= 0 || remaining > Constants.NAV_MAX_AGE_MS) {
                showError(context, "取餐卡片已过期，请重新进入订单页");
                return true;
            }
            String appId = data.getString("appId");
            String path = data.getString("path");
            if (!appId.matches("gh_[A-Za-z0-9_]{1,96}") || path.length() > 2048) return true;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastTap < 800) return true;
            lastTap = now;
            if (Constants.PKG_AICR.equals(context.getPackageName())) {
                // Stable, unobfuscated public ABI used by Xiaomi's own OpenAppOpt.
                Class<?> type = Class.forName("com.xiaomi.aireco.focus.notify.action.VoiceAssistantAbility", false, loader);
                Object ability = type.getField("INSTANCE").get(null);
                type.getMethod("sendLaunchWeXinSmallProgram", String.class, String.class)
                        .invoke(ability, appId, path);
                log("requested official WeChat launch (without notification database lookup)");
            } else {
                // The class name is obfuscated; resolution stays off the receiver/UI thread.
                Context app = context.getApplicationContext();
                new Thread(() -> {
                    try {
                        Method method = resolveVoiceMethod(app, loader);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                boolean sent = Boolean.TRUE.equals(method.invoke(null, appId, path));
                                log("official WeChat SDK accepted=" + sent);
                                if (!sent) showError(app, "微信未接受跳转请求，请重新进入订单页");
                            } catch (Throwable error) { failed(app, error); }
                        });
                    } catch (Throwable error) { failed(app, error); }
                }, "MioPickupNavigation").start();
            }
        } catch (Throwable error) { failed(context, error); }
        return true;
    }

    private static Method resolveVoiceMethod(Context context, ClassLoader loader) throws Exception {
        if (voiceMethod != null) return voiceMethod;
        try { return voiceMethod = matchingMethod(Class.forName("com.xiaomi.voiceassistant.utils.s2", false, loader)); }
        catch (ReflectiveOperationException ignored) { }
        // Match the stable method name + full signature; never guess an obfuscated method.
        Method found = null;
        DexFile dex = new DexFile(context.getApplicationInfo().sourceDir);
        try {
            Enumeration<String> entries = dex.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement();
                if (!name.startsWith("com.xiaomi.voiceassistant.utils.")) continue;
                try {
                    Method candidate = matchingMethod(Class.forName(name, false, loader));
                    if (found != null) throw new IllegalStateException("Ambiguous official WeChat bridge");
                    found = candidate;
                } catch (ReflectiveOperationException | LinkageError ignored) { }
            }
        } finally { dex.close(); }
        if (found == null) throw new NoSuchMethodException("Official WeChat bridge changed");
        return voiceMethod = found;
    }

    private static Method matchingMethod(Class<?> type) throws NoSuchMethodException {
        Method method = type.getMethod("openWeChatSmallProgram", String.class, String.class);
        if (method.getReturnType() != boolean.class || !Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException("Wrong WeChat bridge signature");
        }
        return method;
    }
    private static void failed(Context context, Throwable error) {
        log("launch failed safely: " + error.getClass().getSimpleName());
        showError(context, "暂时无法打开小程序，请重新进入订单页");
    }
    private static void showError(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try { Toast.makeText(context, message, Toast.LENGTH_SHORT).show(); }
            catch (Throwable ignored) { }
        });
    }
    private static void log(String text) { XposedBridge.log("AutoPickupIsland/Navigation: " + text); }
    private NativeWechatNavigation() { }
}
