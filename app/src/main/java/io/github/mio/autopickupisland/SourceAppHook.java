package io.github.mio.autopickupisland;

import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.widget.TextView;

import org.json.JSONTokener;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

final class SourceAppHook {
    private static final String JS_INTERFACE = "__mioPickupBridge_3f72";
    private static final Map<View, DomBridge> BRIDGES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ConcurrentHashMap<String, Long> LAST_SENT = new ConcurrentHashMap<>();
    private static volatile WeakReference<Activity> resumed = new WeakReference<>(null);
    private static volatile String packageName = "";
    private static volatile boolean installed;
    private static volatile boolean nativeTextHooked;
    private static Handler main;

    private SourceAppHook() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (installed) return;
        installed = true;
        packageName = lpparam.packageName;
        main = new Handler(Looper.getMainLooper());

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Application app = (Application) param.thisObject;
                        app.registerActivityLifecycleCallbacks(new Lifecycle());
                        app.registerReceiver(new BroadcastReceiver() {
                            @Override public void onReceive(Context context, Intent intent) {
                                if (!Constants.ACTION_ROUTE_REQUEST.equals(intent.getAction())) return;
                                String sender = BroadcastVerifier.sender(this, context);
                                if (!Constants.PKG_AICR.equals(sender)
                                        && !Constants.PKG_VOICE_ASSIST.equals(sender)) return;
                                Activity activity = resumed.get();
                                if (activity != null) schedule(activity, 0L);
                            }
                        }, new IntentFilter(Constants.ACTION_ROUTE_REQUEST),
                                Context.RECEIVER_EXPORTED);
                        RuleRepository repository = RuleRepository.get(app);
                        if (Constants.PKG_WECHAT.equals(packageName)
                                || Constants.PKG_ALIPAY.equals(packageName)
                                || repository.hasWebRules(packageName)) {
                            hookWebViewClasses(lpparam.classLoader);
                        }
                        if (repository.hasNativeRules(packageName)) hookNativeTextChanges();
                        log("active in " + lpparam.processName);
                    }
                });

        XposedBridge.hookAllMethods(Activity.class, "onWindowFocusChanged",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && Boolean.TRUE.equals(param.args[0])) {
                            Activity activity = (Activity) param.thisObject;
                            if (activity == resumed.get()) schedule(activity, 500L);
                        }
                    }
                });
    }

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity activity) {
            resumed = new WeakReference<>(activity);
            schedule(activity, 500L);
            schedule(activity, 1_500L);
        }

        @Override public void onActivityPaused(Activity activity) {
            if (resumed.get() == activity) resumed = new WeakReference<>(null);
        }

        @Override public void onActivityCreated(Activity a, Bundle b) { }
        @Override public void onActivityStarted(Activity a) { }
        @Override public void onActivityStopped(Activity a) { }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
        @Override public void onActivityDestroyed(Activity a) { }
    }

    private static void schedule(Activity activity, long delay) {
        if (activity == null || main == null) return;
        WeakReference<Activity> reference = new WeakReference<>(activity);
        main.postDelayed(() -> {
            Activity current = reference.get();
            if (current != null && !current.isFinishing() && !current.isDestroyed()
                    && resumed.get() == current) scan(current);
        }, delay);
    }

    private static void scan(Activity activity) {
        try {
            RuleRepository repository = RuleRepository.get(activity);
            String activityName = activity.getClass().getName();
            Rule nativeRule = repository.matchNative(packageName, activityName);
            if (nativeRule != null) {
                Rule.Target target = repository.activityTarget(nativeRule, activityName);
                String text = collectNativeText(activity.getWindow().getDecorView(),
                        target != null && target.ignoreVisibility);
                if (!text.isEmpty()) {
                    PickupEvent event = nativeRule.event(activityName, "", activityName, "", "",
                            text, target, repository.version());
                    publishDeduplicated(activity, event);
                }
            }

            if (Constants.PKG_WECHAT.equals(packageName)
                    || Constants.PKG_ALIPAY.equals(packageName)
                    || repository.hasWebRules(packageName)) {
                for (View view : findWebViews(activity.getWindow().getDecorView())) {
                    evaluate(view, extractionScript(Constants.PKG_ALIPAY.equals(packageName)),
                            value -> handleJs(activity, view, decodeResult(value)));
                }
            }
        } catch (Throwable error) {
            log("scan failed: " + error);
        }
    }

    private static void handleJs(Activity activity, View webView, String raw) {
        if (raw.isEmpty()) return;
        try {
            JSONObject json = new JSONObject(raw);
            String href = json.optString("href");
            String route = PickupEvent.observedPath(json.optString("route"));
            String appId = Constants.PKG_ALIPAY.equals(packageName)
                    ? json.optString("aliAppId") : json.optString("appId");
            String query = json.optString("query");
            if ((Constants.PKG_WECHAT.equals(packageName)
                    || Constants.PKG_ALIPAY.equals(packageName)) && !appId.isEmpty()) {
                PickupEvent nav = PickupEvent.navigation(packageName, activity.getClass().getName(),
                        appId, route, query, href);
                publishDeduplicated(activity, nav);
            }

            RuleRepository repository = RuleRepository.get(activity);
            Rule rule = repository.matchMini(packageName, appId, route);
            Rule.Target target = repository.pathTarget(rule, route);
            if (rule == null || target == null) return;
            String body = json.optString("body");
            String portal = json.optString("portal");
            String text = rule.extractRootPortal && !portal.isEmpty()
                    ? portal + '\n' + body : body;
            text = PickupEvent.truncate(text, Constants.MAX_CONTENT_LENGTH);
            if (!text.isEmpty()) {
                PickupEvent event = rule.event(activity.getClass().getName(), appId, route, query,
                        href, text, target, repository.version());
                publishDeduplicated(activity, event);
            }
            installObserver(activity, webView,
                    Constants.PKG_ALIPAY.equals(packageName));
        } catch (Throwable error) {
            log("JS result failed: " + error);
        }
    }

    private static void installObserver(Context context, View webView, boolean alipay) {
        DomBridge bridge = BRIDGES.get(webView);
        if (bridge == null) {
            bridge = new DomBridge(context.getApplicationContext(), alipay);
            if (!invokeByName(webView, "addJavascriptInterface", bridge, JS_INTERFACE)) return;
            BRIDGES.put(webView, bridge);
        }
        String token = bridge.token;
        String script = "(function(){try{" +
                "if(window.__mioPickupObserver20260225){window.__mioPickupKick20260225();return 'ok';}" +
                "var timer=0;var send=function(){try{" + extractionObject(alipay) +
                "window['" + JS_INTERFACE + "'].onPage('" + token + "',JSON.stringify(r));" +
                "}catch(e){}};" +
                "var kick=function(){clearTimeout(timer);timer=setTimeout(send,650);};" +
                "window.__mioPickupKick20260225=kick;" +
                "var o=new MutationObserver(kick);" +
                "o.observe(document.documentElement,{subtree:true,childList:true,characterData:true});" +
                "window.__mioPickupObserver20260225=o;kick();return 'installed';" +
                "}catch(e){return String(e);}})()";
        evaluate(webView, script, ignored -> { });
    }

    public static final class DomBridge {
        private final Context context;
        private final boolean alipay;
        private final String token = UUID.randomUUID().toString().replace("-", "");
        private long lastCall;

        DomBridge(Context context, boolean alipay) {
            this.context = context;
            this.alipay = alipay;
        }

        @JavascriptInterface
        public synchronized void onPage(String suppliedToken, String json) {
            long now = System.currentTimeMillis();
            if (!token.equals(suppliedToken) || now - lastCall < 900L || json == null
                    || json.length() > 100_000) return;
            lastCall = now;
            main.post(() -> {
                Activity activity = resumed.get();
                if (activity != null) handleJs(activity, findBridgeView(this), json);
            });
        }
    }

    private static View findBridgeView(DomBridge bridge) {
        synchronized (BRIDGES) {
            for (Map.Entry<View, DomBridge> entry : BRIDGES.entrySet()) {
                if (entry.getValue() == bridge) return entry.getKey();
            }
        }
        return null;
    }

    private static void publishDeduplicated(Context context, PickupEvent event) {
        attachSourceOpenIntent(context, event);
        BridgeClient.shareRouteWithRunningSystemApps(context, event);
        String key = event.navigationOnly
                ? "nav:" + event.navigationKey()
                : "event:" + event.navigationKey() + ':' + Integer.toHexString(event.content.hashCode());
        long now = System.currentTimeMillis();
        long window = event.navigationOnly ? 300_000L : 30_000L;
        Long previous = LAST_SENT.put(key, now);
        if (previous != null && now - previous < window) return;
        event.timestamp = now;
        if (!BridgeClient.publish(context, event)) log("provider rejected " + key);
    }

    private static void attachSourceOpenIntent(Context context, PickupEvent event) {
        if (!(context instanceof Activity activity)
                || Constants.PKG_WECHAT.equals(event.sourcePackage)
                || Constants.PKG_ALIPAY.equals(event.sourcePackage)
                || !event.sourcePackage.equals(activity.getPackageName())) return;
        try {
            Intent target = new Intent(activity.getIntent());
            String targetClass = event.launchPath;
            if (targetClass.isEmpty() || !targetClass.contains(".")) targetClass = event.activity;
            target.setClassName(event.sourcePackage, targetClass)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (Constants.PKG_TEST_FIXTURE.equals(event.sourcePackage)) {
                target.putExtra("mio_from_island", true);
            }
            int request = 0x4d000000 | (event.navigationKey().hashCode() & 0x00ffffff);
            event.sourceOpenIntent = PendingIntent.getActivity(activity, request, target,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable ignored) {
        }
    }

    private static String extractionScript(boolean alipay) {
        return "(function(){try{" + extractionObject(alipay) +
                "return JSON.stringify(r);}catch(e){return '';}})()";
    }

    private static String extractionObject(boolean alipay) {
        String route = alipay
                ? "var h=href.indexOf('#'),q=href.indexOf('?',h);if(h>=0)route=href.substring(h+1,q>h?q:href.length);"
                : "route=String(window.__route__||'');";
        return "var href=String(window.location.href||''),route='';" + route +
                "var body=(document.body&&document.body.innerText)||'',portal='';" +
                "var roots=document.querySelectorAll('wx-root-portal-content');" +
                "if(roots&&roots.length){portal=Array.from(roots).map(function(e){return (e.innerText||'').trim();}).filter(Boolean).join('\\n');}" +
                "var ali='';try{ali=String(window.$HybridDataMineManager.envInfo.appId||'');}catch(e){}" +
                "var r={href:href,route:route,appId:String(window.__appId__||''),aliAppId:ali,query:String(window.__queryString__||''),body:body.substring(0,40000),portal:portal.substring(0,40000)};";
    }

    private static String decodeResult(String value) {
        if (value == null || value.equals("null")) return "";
        try {
            Object decoded = new JSONTokener(value).nextValue();
            return decoded instanceof String ? (String) decoded : value;
        } catch (Throwable ignored) {
            return value;
        }
    }

    private static void evaluate(View webView, String script, ValueCallback<String> callback) {
        if (webView == null) return;
        try {
            Method method = findMethod(webView.getClass(), "evaluateJavascript", 2);
            if (method == null) return;
            method.setAccessible(true);
            method.invoke(webView, script, callback);
        } catch (Throwable error) {
            log("evaluateJavascript failed: " + error.getClass().getSimpleName());
        }
    }

    private static boolean invokeByName(Object receiver, String name, Object... args) {
        try {
            Method method = findMethod(receiver.getClass(), name, args.length);
            if (method == null) return false;
            method.setAccessible(true);
            method.invoke(receiver, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name)
                        && method.getParameterTypes().length == parameterCount) return method;
            }
        }
        return null;
    }

    private static LinkedHashSet<View> findWebViews(View root) {
        LinkedHashSet<View> result = new LinkedHashSet<>();
        if (root == null) return result;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 8_000) {
            View view = queue.removeFirst();
            String name = view.getClass().getName().toLowerCase(Locale.ROOT);
            if (name.contains("webview") || findMethod(view.getClass(), "evaluateJavascript", 2) != null) {
                result.add(view);
            }
            if (view instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return result;
    }

    private static String collectNativeText(View root, boolean includeInvisible) {
        if (root == null) return "";
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        int length = 0;
        while (!queue.isEmpty() && visited++ < 10_000 && length < Constants.MAX_CONTENT_LENGTH) {
            View view = queue.removeFirst();
            if (!includeInvisible && (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f)) {
                continue;
            }
            if (view instanceof TextView textView) {
                String text = PickupEvent.clean(String.valueOf(textView.getText()));
                if (!text.isEmpty() && lines.add(text)) length += text.length();
            }
            CharSequence description = view.getContentDescription();
            String desc = description == null ? "" : PickupEvent.clean(description.toString());
            if (!desc.isEmpty() && lines.add(desc)) length += desc.length();
            if (view instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return PickupEvent.truncate(String.join("\n", lines), Constants.MAX_CONTENT_LENGTH);
    }

    private static void hookWebViewClasses(ClassLoader loader) {
        String[] names = {"com.tencent.xweb.WebView", "com.uc.webview.export.WebView",
                "android.webkit.WebView"};
        for (String name : names) {
            Class<?> type = XposedHelpers.findClassIfExists(name, loader);
            if (type == null) continue;
            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = resumed.get();
                    if (activity != null) schedule(activity, 650L);
                }
            };
            for (String method : new String[]{"loadUrl", "reload", "onResume"}) {
                try {
                    XposedBridge.hookAllMethods(type, method, callback);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void hookNativeTextChanges() {
        if (nativeTextHooked) return;
        nativeTextHooked = true;
        XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
            private long lastSchedule;

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = resumed.get();
                if (activity == null) return;
                try {
                    if (RuleRepository.get(activity).matchNative(
                            packageName, activity.getClass().getName()) == null) return;
                    long now = SystemClock.uptimeMillis();
                    if (now - lastSchedule < 450L) return;
                    lastSchedule = now;
                    schedule(activity, 650L);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void log(String message) {
        XposedBridge.log("AutoPickupIsland/Source: " + message);
    }
}
