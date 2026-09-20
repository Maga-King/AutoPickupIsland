package io.github.mio.autopickupisland;

import android.app.ActivityManager;
import android.app.BroadcastOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Relays AICR's existing, event-driven foreground callback to the UID-1000 bridge. */
final class AicrTopActivityRelay {
    private static final String OBSERVER_DESCRIPTOR = "android.app.IMiuiActivityObserver";
    private static final int TRANSACTION_RESUMED = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final String OBSERVER =
            "com.xiaomi.aireco.perception.topactivity."
                    + "TopActivityPerception$observer$2$1$1";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean DIRECT_STARTING = new AtomicBoolean();
    private static final AtomicBoolean DIRECT_REGISTERED = new AtomicBoolean();
    private static final AtomicInteger DIRECT_ATTEMPTS = new AtomicInteger();

    private static volatile Context appContext;
    private static volatile Handler mainHandler;
    private static volatile Object directObserverProxy;
    private static volatile TopActivityBinder directObserverBinder;
    private static volatile RuleRepository repository;
    private static volatile long rulesLoadedAt;
    private static volatile long lastRuleErrorAt;
    private static volatile String lastComponent = "";
    private static volatile long lastSentAt;

    private AicrTopActivityRelay() {
    }

    static void install(ClassLoader loader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> observer = XposedHelpers.findClassIfExists(OBSERVER, loader);
            if (observer == null) {
                log("top-activity observer class absent");
                return;
            }
            XposedHelpers.findAndHookMethod(observer, "activityResumed", Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length == 0 || !(param.args[0] instanceof Intent intent)) {
                                    return;
                                }
                                dispatch(intent);
                            } catch (Throwable error) {
                                log("relay callback failed: " + error);
                            }
                        }
                    });
            log("event relay installed on AICR TopActivityPerception");
            HookHealth.state("observer_fallback", "原生成类后备 Hook 已安装（仍有版本依赖）");
        } catch (Throwable error) {
            log("OEM observer fallback hook failed: " + error);
        }
    }

    static void onContextReady(Context context) {
        if (context == null) return;
        Context application = context.getApplicationContext();
        appContext = application == null ? context : application;
        mainHandler = new Handler(appContext.getMainLooper());
        ensureDirectObserver();
    }

    private static void dispatch(Intent observed) {
        ComponentName component = observed == null ? null : observed.getComponent();
        if (component == null) return;
        Handler handler = mainHandler;
        if (handler == null) return;
        ComponentName copy = new ComponentName(component.getPackageName(), component.getClassName());
        try {
            handler.post(() -> {
                try {
                    relay(copy);
                } catch (Throwable error) {
                    log("dispatch failed: " + error);
                }
            });
        } catch (Throwable error) {
            log("dispatch post failed: " + error);
        }
    }

    private static void relay(ComponentName component) {
        Context context = appContext;
        if (context == null || component == null) return;

        String packageName = PickupEvent.clean(component.getPackageName());
        String activity = PickupEvent.clean(component.getClassName());
        if (packageName.isEmpty() || !shouldRelay(context, packageName)) return;

        long now = System.currentTimeMillis();
        String key = packageName + '/' + activity;
        if (key.equals(lastComponent) && now - lastSentAt < 500L) return;
        lastComponent = key;
        lastSentAt = now;

        Intent request = new Intent(Constants.ACTION_CAPTURE_REQUEST)
                .setComponent(new ComponentName(Constants.PKG_PRIVILEGED_ASSIST,
                        Constants.PRIVILEGED_ASSIST_RECEIVER))
                .putExtra(Constants.EXTRA_SOURCE_PACKAGE, packageName)
                .putExtra(Constants.EXTRA_SOURCE_ACTIVITY, activity)
                .putExtra(Constants.EXTRA_OBSERVED_AT, now)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        try {
            BroadcastOptions options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true);
            context.sendBroadcast(request, null, options.toBundle());
            log("relayed " + packageName + "/" + simpleName(activity));
        } catch (Throwable error) {
            log("capture broadcast failed: " + error);
        }
    }

    /**
     * AICR normally registers this same OEM observer lazily. Registering our own small callback
     * removes that lifecycle dependency while still using the existing privileged app boundary.
     */
    private static void ensureDirectObserver() {
        if (DIRECT_REGISTERED.get() || !DIRECT_STARTING.compareAndSet(false, true)) return;
        int attempt = DIRECT_ATTEMPTS.incrementAndGet();
        Thread thread = new Thread(() -> registerDirectObserver(attempt),
                "MioTopActivityRegister");
        thread.setDaemon(true);
        try {
            thread.start();
        } catch (Throwable error) {
            DIRECT_STARTING.set(false);
            log("direct observer thread failed: " + error);
        }
    }

    private static void registerDirectObserver(int attempt) {
        try {
            Class<?> observerInterface = XposedHelpers.findClass(
                    "android.app.IMiuiActivityObserver", null);
            TopActivityBinder callbackBinder = new TopActivityBinder();
            Object observer = Proxy.newProxyInstance(
                    AicrTopActivityRelay.class.getClassLoader(),
                    new Class<?>[]{observerInterface},
                    (proxy, method, args) -> directProxyCall(
                            callbackBinder, proxy, method.getName(), args));

            Class<?> activityTaskManagerClass = XposedHelpers.findClass(
                    "android.app.ActivityTaskManager", null);
            Object service = XposedHelpers.callStaticMethod(
                    activityTaskManagerClass, "getService");
            if (service == null) throw new IllegalStateException("ATMS binder unavailable");
            Class<?> serviceInterface = XposedHelpers.findClass(
                    "android.app.IActivityTaskManager", null);
            Method register = serviceInterface.getMethod("registerActivityObserver",
                    observerInterface, Intent.class);
            register.setAccessible(true);
            register.invoke(service, observer, new Intent());

            directObserverBinder = callbackBinder;
            directObserverProxy = observer;
            DIRECT_REGISTERED.set(true);
            DIRECT_STARTING.set(false);
            log("direct OEM activity observer registered");
            HookHealth.state("observer_direct", "系统页面观察接口已注册（不依赖生成类名）");
            seedForeground();
        } catch (Throwable error) {
            DIRECT_STARTING.set(false);
            log("direct observer attempt " + attempt + " failed: " + unwrap(error));
            Handler handler = mainHandler;
            if (attempt < 3 && handler != null) {
                try {
                    handler.postDelayed(AicrTopActivityRelay::ensureDirectObserver, 2_000L);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Object directProxyCall(TopActivityBinder binder, Object proxy,
                                          String name, Object[] args) {
        try {
            return switch (name) {
                case "asBinder" -> binder;
                case "activityResumed" -> {
                    if (args != null && args.length == 1 && args[0] instanceof Intent intent) {
                        dispatch(intent);
                    }
                    yield null;
                }
                case "activityIdle", "activityPaused", "activityStopped",
                        "activityDestroyed" -> null;
                case "toString" -> "MioTopActivityObserver";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> args != null && args.length == 1 && proxy == args[0];
                default -> null;
            };
        } catch (Throwable error) {
            log("direct observer callback failed: " + error);
            return null;
        }
    }

    private static void seedForeground() {
        Context context = appContext;
        if (context == null) return;
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            List<ActivityManager.RunningTaskInfo> tasks = manager == null
                    ? null : manager.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return;
            ComponentName top = tasks.get(0).topActivity;
            if (top != null) dispatch(new Intent().setComponent(top));
        } catch (Throwable error) {
            log("seed foreground failed: " + error);
        }
    }

    private static boolean shouldRelay(Context context, String packageName) {
        if (Constants.isSourcePackage(packageName)) return true;
        try {
            RuleRepository current = RuleRepository.get(context);
            return current.hasRules(packageName);
        } catch (Throwable error) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastRuleErrorAt >= 60_000L) {
                lastRuleErrorAt = now;
                log("rule membership check failed: " + error);
            }
            return false;
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof InvocationTargetException
                && ((InvocationTargetException) current).getCause() != null) {
            current = ((InvocationTargetException) current).getCause();
        }
        return current;
    }

    private static String simpleName(String value) {
        int index = value == null ? -1 : value.lastIndexOf('.');
        return index < 0 ? PickupEvent.clean(value) : value.substring(index + 1);
    }

    /** Android 17/HypeOS transaction layout verified with baksmali from framework.jar. */
    private static final class TopActivityBinder extends Binder {
        TopActivityBinder() {
            attachInterface(null, OBSERVER_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                if (code >= IBinder.FIRST_CALL_TRANSACTION
                        && code <= IBinder.LAST_CALL_TRANSACTION) {
                    data.enforceInterface(OBSERVER_DESCRIPTOR);
                }
                if (code >= IBinder.FIRST_CALL_TRANSACTION
                        && code <= IBinder.FIRST_CALL_TRANSACTION + 4) {
                    Intent observed = data.readTypedObject(Intent.CREATOR);
                    data.enforceNoDataAvail();
                    if (code == TRANSACTION_RESUMED) dispatch(observed);
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable error) {
                log("direct observer binder failed: " + error);
                return true;
            }
        }
    }

    private static void log(String message) {
        XposedBridge.log("AutoPickupIsland/Relay: " + message);
    }
}
