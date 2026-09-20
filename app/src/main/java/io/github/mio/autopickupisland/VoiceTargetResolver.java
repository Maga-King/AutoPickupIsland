package io.github.mio.autopickupisland;
import android.content.Context;
import android.app.PendingIntent;
import java.io.File;
import java.lang.reflect.*;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;

/** No short class-name dependency. A scan runs off-main once per APK identity. */
final class VoiceTargetResolver {
    static Class<?> find(Context context, ClassLoader loader) throws Exception {
        var app = context.getApplicationInfo();
        var pkg = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        File apk = new File(app.sourceDir);
        String identity = "voice-v1:" + pkg.getLongVersionCode() + ":" + pkg.lastUpdateTime
                + ":" + apk.getAbsolutePath() + ":" + apk.length() + ":" + apk.lastModified();
        java.util.ArrayList<String> paths = new java.util.ArrayList<>(); paths.add(app.sourceDir);
        if (app.splitSourceDirs != null) for (String split : app.splitSourceDirs) {
            File f = new File(split); identity += ":" + split + ":" + f.length() + ":" + f.lastModified(); paths.add(split);
        }
        var prefs = context.getSharedPreferences("mio_hook_targets_v1", Context.MODE_PRIVATE);
        Class<?> agent = Class.forName("com.xiaomi.ai.api.Agent$SuperIsland", false, loader);
        if (identity.equals(prefs.getString("identity", ""))) {
            try {
                Class<?> cached = Class.forName(prefs.getString("manager", ""), false, loader);
                if (valid(cached, agent)) { HookHealth.state("manager", "版本缓存命中，结构校验通过"); return cached; }
            } catch (ReflectiveOperationException ignored) { }
        }
        try { System.loadLibrary("dexkit"); }
        catch (UnsatisfiedLinkError error) {
            System.load(HookEntry.modulePath + "!/lib/" + android.os.Build.SUPPORTED_ABIS[0] + "/libdexkit.so");
        }
        Class<?> found = null;
        for (String path : paths) {
            try (DexKitBridge dex = DexKitBridge.create(path)) {
                var matches = dex.findClass(FindClass.create().matcher(ClassMatcher.create()
                        .usingStrings(java.util.List.of("MemorySceneIslandManager", "memory_island_channel", "param_island"))));
                for (var match : matches) {
                    Class<?> candidate = match.getInstance(loader);
                    if (!valid(candidate, agent)) continue;
                    if (found != null && found != candidate) throw new IllegalStateException("AmbiguousVoiceManager");
                    found = candidate;
                }
            }
        }
        if (found == null) throw new ClassNotFoundException("VoiceManagerFingerprintNotFound");
        prefs.edit().clear().putString("identity", identity).putString("manager", found.getName()).apply();
        HookHealth.state("manager", "DexKit 特征唯一匹配，结构校验通过");
        return found;
    }
    static boolean valid(Class<?> type, Class<?> agent) {
        if (VoiceAssistNativeIsland.findNotifyMethod(type, agent) == null) return false;
        int confirms = 0;
        for (Method m : type.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == Context.class && p[1] == int.class
                    && PendingIntent.class.isAssignableFrom(m.getReturnType())) confirms++;
        }
        return confirms == 1;
    }
}
