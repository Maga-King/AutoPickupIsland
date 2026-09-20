package io.github.mio.autopickupisland;

import android.app.ActivityManager;
import android.app.BroadcastOptions;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Requests AssistStructure from Xiaomi's isolated UID-1000 AI-call application.
 *
 * This deliberately hooks only that application's own manifest receiver. It never enters
 * system_server, SystemUI, WeChat, Alipay, QQ, or the fixture process. Work is event driven and
 * bounded to a short, increasingly sparse capture window after AICR reports a supported
 * foreground activity. Leaving the target task cancels the window immediately.
 */
final class PrivilegedAssistHook {
    private static final String ASSIST_RECEIVER_DESCRIPTOR =
            "android.app.IAssistDataReceiver";
    private static final int TRANSACTION_ASSIST_DATA = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_ASSIST_SCREENSHOT = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int MAX_CONSECUTIVE_FAILURES = 8;
    private static final long SIGNAL_MAX_AGE_MS = 30_000L;
    private static final long REQUEST_TIMEOUT_MS = 3_000L;
    // Two UIAgent calls, each bounded to 5.5 seconds, plus callback dispatch.
    private static final long DOM_DRAIN_TIMEOUT_MS = 12_000L;
    private static final long[] CAPTURE_DELAYS_MS = {
            400L, 700L, 1_100L, 1_800L, 3_000L, 5_000L, 8_000L, 13_000L, 21_000L
    };
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicInteger CONSECUTIVE_FAILURES = new AtomicInteger();
    private static final AtomicLong REQUEST_IDS = new AtomicLong();
    private static final Object WORKER_LOCK = new Object();

    private static volatile Handler worker;
    private static volatile RuleRepository repository;
    private static volatile Object activityTaskManager;
    private static volatile Class<?> assistReceiverInterface;
    private static volatile Method requestAssistMethod;
    private static volatile Watch activeWatch;
    private static volatile long lastErrorLogAt;
    private static volatile boolean circuitOpen;
    private static volatile Object domSignalSession;

    private PrivilegedAssistHook() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!Constants.PKG_PRIVILEGED_ASSIST.equals(lpparam.packageName)
                || !Constants.PKG_PRIVILEGED_ASSIST.equals(lpparam.processName)
                || !INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> receiverClass = XposedHelpers.findClassIfExists(
                    Constants.PRIVILEGED_ASSIST_RECEIVER, lpparam.classLoader);
            if (receiverClass == null) {
                INSTALLED.set(false);
                log("manifest receiver class absent; bridge disabled");
                return;
            }
            XposedBridge.hookAllMethods(receiverClass, "onReceive", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 2
                                || !(param.thisObject instanceof BroadcastReceiver receiver)
                                || !(param.args[0] instanceof Context context)
                                || !(param.args[1] instanceof Intent intent)) {
                            return;
                        }
                        if (Constants.ACTION_CONFIG_CHANGED.equals(intent.getAction())
                                || Constants.ACTION_CLEAR_STATE.equals(intent.getAction())) {
                            param.setResult(null);
                            if (BroadcastVerifier.isPackage(receiver, context, Constants.MODULE_PACKAGE)) {
                                postSafe(worker(), () -> {
                                    cancelActive();
                                    repository = null;
                                    RuleRepository.invalidate();
                                    log("configuration changed: invalidated cached policy and pending capture");
                                });
                            }
                            return;
                        }
                        if (!Constants.ACTION_CAPTURE_REQUEST.equals(intent.getAction())) return;
                        // The OEM receiver does not know this private action. Keep its code path out
                        // of the request entirely, including for malformed or untrusted broadcasts.
                        param.setResult(null);
                        onCaptureSignal(receiver, context, intent);
                    } catch (Throwable error) {
                        fail("receiver callback", error);
                    }
                }
            });
            log("installed in isolated AI-call app; system_server hook is not used");
            HookHealth.state("reader", "AI 通话 Receiver Hook 已安装");
        } catch (Throwable error) {
            INSTALLED.set(false);
            fail("install", error);
        }
    }

    private static void onCaptureSignal(BroadcastReceiver receiver, Context context,
                                        Intent intent) {
        if (circuitOpen || receiver == null || context == null || intent == null) return;
        boolean fromAicr = BroadcastVerifier.isPackage(
                receiver, context, Constants.PKG_AICR);
        boolean fromModule = BroadcastVerifier.isPackage(
                receiver, context, Constants.MODULE_PACKAGE);
        if (!fromAicr && !fromModule) {
            String sender = "";
            int senderUid = -1;
            try {
                sender = BroadcastVerifier.sender(receiver, context);
                senderUid = receiver.getSentFromUid();
            } catch (Throwable ignored) {
            }
            log("ignored capture signal from uid=" + senderUid + " package=" + sender);
            return;
        }

        String packageName = PickupEvent.truncate(PickupEvent.clean(
                intent.getStringExtra(Constants.EXTRA_SOURCE_PACKAGE)), 200);
        String activity = PickupEvent.truncate(PickupEvent.clean(
                intent.getStringExtra(Constants.EXTRA_SOURCE_ACTIVITY)), 400);
        long observedAt = intent.getLongExtra(Constants.EXTRA_OBSERVED_AT, 0L);
        long age = System.currentTimeMillis() - observedAt;
        if (packageName.isEmpty() || age < -5_000L || age > SIGNAL_MAX_AGE_MS) return;

        Context application = context.getApplicationContext();
        Context safeContext = application == null ? context : application;
        Signal signal = new Signal(safeContext, packageName, activity, observedAt,
                fromModule && intent.getBooleanExtra(Constants.EXTRA_CONTENT_CHANGED, false));
        if (!signal.contentChange) log("accepted capture signal " + packageName + "/" + simpleName(activity));
        postSafe(worker(), () -> arm(signal));
    }

    private static void arm(Signal signal) {
        if (circuitOpen || signal == null) return;
        TaskSnapshot snapshot = findForegroundTask(signal);
        if (snapshot == null) {
            cancelActive();
            return;
        }

        RuleRepository rules;
        try {
            rules = rules(signal.context);
        } catch (Throwable error) {
            fail("load ColorOS rules", error);
            cancelActive();
            return;
        }
        if (rules == null || !rules.hasRules(snapshot.packageName)) {
            cancelActive();
            return;
        }

        Rule nativeRule = rules.matchNative(snapshot.packageName, snapshot.activity);
        if (Constants.PKG_TEST_FIXTURE.equals(snapshot.packageName)
                && FixtureCatalog.ACTIVITY.equals(snapshot.activity)) {
            try { nativeRule = FixtureCatalog.match(signal.context, snapshot.packageName, snapshot.activity, snapshot.label); }
            catch (Exception error) { log("fixture catalog lookup failed safely"); }
        }
        boolean webCandidate = nativeRule == null
                && rules.hasWebRules(snapshot.packageName)
                && isWebContainer(snapshot.packageName, snapshot.activity);
        if (nativeRule == null && !webCandidate) {
            cancelActive();
            return;
        }
        if (webCandidate && Constants.PKG_WECHAT.equals(snapshot.packageName)
                && !rules.hasPageScope(snapshot.packageName, snapshot.label)) {
            cancelActive();
            return;
        }

        Watch current = activeWatch;
        long now = SystemClock.elapsedRealtime();
        if (current != null && !current.cancelled
                && current.snapshot.taskId == snapshot.taskId
                && current.snapshot.packageName.equals(snapshot.packageName)
                && current.snapshot.activity.equals(snapshot.activity)
                && current.snapshot.label.equals(snapshot.label)) {
            if (signal.contentChange) {
                pulse(current);
                return;
            }
            if (now - current.armedAt < 1_500L) return;
        }
        cancelActive();
        Watch watch = new Watch(snapshot, nativeRule, now, signal.contentChange);
        activeWatch = watch;
        schedule(watch, CAPTURE_DELAYS_MS[0]);
        log("armed task=" + snapshot.taskId + " " + snapshot.packageName + "/"
                + simpleName(snapshot.activity) + (nativeRule == null ? " web" : " native"));
    }

    private static RuleRepository rules(Context context) {
        RuleRepository current = RuleRepository.get(context);
        if (current == repository) return current;
        repository = current;
        log("loaded ColorOS rules " + current.version() + " (" + current.size() + ")");
        return current;
    }

    private static TaskSnapshot findForegroundTask(Signal signal) {
        try {
            ActivityManager manager = signal.context.getSystemService(ActivityManager.class);
            if (manager == null) return null;
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(4);
            if (tasks == null || tasks.isEmpty()) return null;
            ActivityManager.RunningTaskInfo task = tasks.get(0);
            ComponentName top = task == null ? null : task.topActivity;
            if (top == null || !signal.packageName.equals(top.getPackageName())) return null;
            String activity = PickupEvent.clean(top.getClassName());
            String label = "";
            try {
                ActivityManager.TaskDescription description = task.taskDescription;
                label = description == null ? "" : PickupEvent.clean(description.getLabel());
            } catch (Throwable ignored) {
            }
            return new TaskSnapshot(signal.context, task.taskId, signal.packageName,
                    activity.isEmpty() ? signal.activity : activity, label);
        } catch (Throwable error) {
            fail("read foreground task", error);
            return null;
        }
    }

    private static boolean isWebContainer(String packageName, String activity) {
        String value = PickupEvent.clean(activity).toLowerCase(Locale.ROOT);
        if (Constants.PKG_WECHAT.equals(packageName)) {
            return value.contains(".plugin.appbrand.ui.appbrand")
                    || value.contains(".appbrand.launching.");
        }
        if (Constants.PKG_ALIPAY.equals(packageName)) {
            return value.contains("xriver") || value.contains("griver")
                    || value.contains("nebula") || value.contains("h5activity")
                    || value.contains("miniapp");
        }
        if (Constants.PKG_TEST_FIXTURE.equals(packageName)) {
            return value.endsWith(".webpickupactivity");
        }
        return true;
    }

    private static boolean stillForeground(Watch watch) {
        if (!isActive(watch)) return false;
        return snapshotForeground(watch.snapshot);
    }

    private static boolean snapshotForeground(TaskSnapshot snapshot) {
        try {
            ActivityManager manager = snapshot.context
                    .getSystemService(ActivityManager.class);
            if (manager == null) return false;
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return false;
            ActivityManager.RunningTaskInfo task = tasks.get(0);
            ComponentName top = task == null ? null : task.topActivity;
            return task != null && task.taskId == snapshot.taskId
                    && top != null
                    && snapshot.packageName.equals(top.getPackageName())
                    && snapshot.activity.equals(top.getClassName())
                    && snapshot.label.equals(task.taskDescription == null ? ""
                            : PickupEvent.clean(task.taskDescription.getLabel()));
        } catch (Throwable error) {
            fail("verify foreground task", error);
            return false;
        }
    }

    private static void schedule(Watch watch, long delayMs) {
        if (!isActive(watch) || circuitOpen) return;
        Runnable next = () -> {
            try {
                capture(watch);
            } catch (Throwable error) {
                fail("capture", error);
                finish(watch);
            }
        };
        watch.next = next;
        worker().postDelayed(next, delayMs);
    }

    private static void capture(Watch watch) {
        if (circuitOpen || !isActive(watch) || !stillForeground(watch)) {
            finish(watch);
            return;
        }
        if (watch.singleShot) { pulse(watch); return; }
        if (watch.round >= CAPTURE_DELAYS_MS.length) {
            finish(watch);
            return;
        }

        requestAssist(watch);
        watch.round++;
        if (watch.round < CAPTURE_DELAYS_MS.length) {
            schedule(watch, CAPTURE_DELAYS_MS[watch.round]);
        } else {
            scheduleDrain(watch);
        }
    }

    private static void pulse(Watch watch) {
        if (!stillForeground(watch)) { finish(watch); return; }
        if (watch.pending != null || watch.domBusy) { watch.contentDirty = true; return; }
        requestAssist(watch);
        if (watch.singleShot || watch.round >= CAPTURE_DELAYS_MS.length) scheduleDrain(watch);
    }

    private static void scheduleDrain(Watch watch) {
        if (!isActive(watch)) return;
        if (watch.next != null) worker().removeCallbacks(watch.next);
        watch.next = () -> { if (isActive(watch)) finish(watch); };
        boolean dom = watch.domBusy || watch.nativeRule == null && Constants.PKG_WECHAT.equals(watch.snapshot.packageName);
        worker().postDelayed(watch.next, dom ? DOM_DRAIN_TIMEOUT_MS : REQUEST_TIMEOUT_MS + 500L);
    }

    private static void scheduleDirtyFollowup(Watch watch) {
        if (!watch.contentDirty || !isActive(watch)) return;
        watch.contentDirty = false;
        Handler current = worker();
        if (watch.followup != null) current.removeCallbacks(watch.followup);
        watch.followup = () -> { if (isActive(watch)) pulse(watch); };
        current.postDelayed(watch.followup, 300L);
        // A trailing page event must not inherit the previous request's cleanup deadline.
        if (watch.singleShot || watch.round >= CAPTURE_DELAYS_MS.length) scheduleDrain(watch);
    }

    private static void requestAssist(Watch watch) {
        if (!isActive(watch) || watch.pending != null || watch.domBusy) return;
        if (watch.nativeRule == null && Constants.PKG_WECHAT.equals(watch.snapshot.packageName)) {
            requestOriginalDom(watch);
            return; // Never substitute Assist text or a configured route when DOM is unavailable.
        }
        if (watch.nativeRule != null && !watch.nativeRule.webView && !watch.nativeFallback
                && io.github.mio.autopickupisland.coloros.ColorOsCollectorScope.nativePackageAllowed(watch.snapshot.packageName)) {
            requestNativeSnapshot(watch);
            return;
        }
        PendingAssist pending = null;
        try {
            Class<?> receiverClass = assistReceiverInterface;
            if (receiverClass == null) {
                receiverClass = Class.forName(ASSIST_RECEIVER_DESCRIPTOR, false, null);
                assistReceiverInterface = receiverClass;
            }
            Object service = activityTaskManager;
            if (service == null) {
                Class<?> activityTaskManagerClass = XposedHelpers.findClass(
                        "android.app.ActivityTaskManager", null);
                service = XposedHelpers.callStaticMethod(activityTaskManagerClass, "getService");
                if (service == null) throw new IllegalStateException("ATMS binder unavailable");
                activityTaskManager = service;
            }
            Method request = requestAssistMethod;
            if (request == null) {
                Class<?> interfaceClass = XposedHelpers.findClass(
                        "android.app.IActivityTaskManager", null);
                request = interfaceClass.getMethod("requestAssistDataForTask",
                        receiverClass, int.class, String.class, String.class, boolean.class);
                request.setAccessible(true);
                requestAssistMethod = request;
            }

            PendingAssist created = new PendingAssist(watch, REQUEST_IDS.incrementAndGet());
            pending = created;
            ReceiverBinder callbackBinder = new ReceiverBinder(created);
            Object receiver = Proxy.newProxyInstance(
                    PrivilegedAssistHook.class.getClassLoader(),
                    new Class<?>[]{receiverClass},
                    (proxy, method, args) -> proxyCall(created, callbackBinder,
                            proxy, method.getName(), args));
            created.callbackBinder = callbackBinder;
            created.receiverProxy = receiver;
            watch.pending = created;

            PendingAssist requestPending = created;
            Runnable timeout = () -> onRequestTimeout(requestPending);
            created.timeout = timeout;
            worker().postDelayed(timeout, REQUEST_TIMEOUT_MS);

            long identity = Binder.clearCallingIdentity();
            Object started;
            try {
                started = request.invoke(service, receiver, watch.snapshot.taskId,
                        Constants.PKG_PRIVILEGED_ASSIST, null, true);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            if (Boolean.FALSE.equals(started)) {
                clearPending(pending);
                watch.misses++;
                log("assist request rejected for task=" + watch.snapshot.taskId);
            }
        } catch (Throwable error) {
            if (pending != null) clearPending(pending);
            fail("request AssistStructure", unwrap(error));
        }
    }

    private static void requestOriginalDom(Watch watch) {
        if (watch.domBusy || !stillForeground(watch)) return;
        watch.domBusy = true;
        try {
            new Thread(() -> {
                OriginalDomPickup.Outcome outcome;
                try {
                    Context context = watch.snapshot.context;
                    int uid = context.getPackageManager().getApplicationInfo(watch.snapshot.packageName, 0).uid;
                    var target = new io.github.mio.autopickupisland.coloros.UiAgentDomProtocol.Target(
                            watch.snapshot.packageName, uid, watch.snapshot.activity);
                    ensurePageSignal(watch, uid, target);
                    outcome = OriginalDomPickup.read(context, target, watch.snapshot.label, rules(context),
                            () -> stillForeground(watch), watch.pageSignal);
                } catch (Throwable ignored) { outcome = new OriginalDomPickup.Outcome("FAILED", null); }
                OriginalDomPickup.Outcome received = outcome;
                postSafe(worker(), () -> {
                    watch.domBusy = false;
                    if (!stillForeground(watch)) return;
                    HookHealth.state("reader_timing", received.timing());
                    HookHealth.state("reader_result", "OK".equals(received.status())
                            ? "内置原版读取器已返回有效 DOM"
                            : "NO_STATUS".equals(received.status())
                            ? "尚未收到内置读取器回执：检查系统框架作用域并重启"
                            : "原版读取结果：" + received.status());
                    if (received.event() != null) {
                        PickupEvent event = received.event();
                        String identity = event.actualAppletId + '\n' + event.route + '\n' + event.query + '\n' + event.content;
                        if (!identity.equals(watch.lastDomIdentity)) {
                            watch.lastDomIdentity = identity;
                            send(watch.snapshot.context, event);
                            log("original DOM forwarded; chars=" + event.content.length());
                            HookHealth.callback("原版 DOM 已读取并转发");
                        }
                        watch.lastDomStatus = "OK";
                        success();
                    } else if (!received.status().equals(watch.lastDomStatus)) {
                        watch.lastDomStatus = received.status();
                        log("original DOM: " + received.status()); // No page text or URL/query logging.
                    }
                    scheduleDirtyFollowup(watch);
                });
            }, "MioOriginalDomRead").start();
        } catch (Throwable error) { watch.domBusy = false; fail("DOM worker", error); }
    }

    private static void requestNativeSnapshot(Watch watch) {
        Rule.Target selected = watch.nativeRule.activityTarget(watch.snapshot.activity);
        if (selected == null || !stillForeground(watch)) return;
        watch.domBusy = true;
        try {
            new Thread(() -> {
                io.github.mio.autopickupisland.coloros.UiAgentNativeProtocol.Result result;
                try {
                    int uid = watch.snapshot.context.getPackageManager().getApplicationInfo(watch.snapshot.packageName, 0).uid;
                    var target = new io.github.mio.autopickupisland.coloros.UiAgentDomProtocol.Target(
                            watch.snapshot.packageName, uid, watch.snapshot.activity);
                    ensurePageSignal(watch, uid, target);
                    result = io.github.mio.autopickupisland.coloros.UiAgentNativeProtocol.read(target,
                            watch.snapshot.taskId, REQUEST_IDS.incrementAndGet(), selected.ignoreVisibility,
                            () -> stillForeground(watch), watch.pageSignal);
                } catch (Throwable ignored) {
                    result = new io.github.mio.autopickupisland.coloros.UiAgentNativeProtocol.Result("UNAVAILABLE", "", 0, -1);
                }
                var received = result;
                postSafe(worker(), () -> {
                    watch.domBusy = false;
                    if (!stillForeground(watch)) return;
                    HookHealth.state("native_reader", "源进程原生采集：" + received.status()
                            + "；ignoreVis=" + selected.ignoreVisibility + "；节点=" + received.visited());
                    HookHealth.state("native_source", received.status()); // Preserve reason if legacy fallback follows.
                    if (received.ok()) {
                        forwardCaptured(watch, received.text(), null);
                        HookHealth.callback("原生控件已跨进程读取并交给规则链");
                    } else if (java.util.Set.of("UNAVAILABLE", "TIMEOUT", "NO_STATUS", "WRONG_BACKEND",
                            "UNSUPPORTED_API", "UNSUPPORTED_VIRTUAL_TREE", "CUSTOM_DISPATCH",
                            "CUSTOM_TEXT_PROVIDER", "MISSING_METADATA", "WORK_LIMIT").contains(received.status())) {
                        // Compatibility fallback, once per active watch. Empty/stale/secure
                        // results must never trigger a less-restricted second read.
                        watch.nativeFallback = true;
                        requestAssist(watch);
                    }
                    scheduleDirtyFollowup(watch);
                });
            }, "MioNativeSnapshotRead").start();
        } catch (Throwable error) { watch.domBusy = false; fail("native worker", error); }
    }
    private static void ensurePageSignal(Watch watch, int uid,
            io.github.mio.autopickupisland.coloros.UiAgentDomProtocol.Target target) {
        if (watch.pageSignal != null) return;
        Object session = new Object();
        domSignalSession = session;
        watch.pageSignal = new io.github.mio.autopickupisland.coloros.UiAgentPageSignal(uid,
                () -> !circuitOpen && domSignalSession == session,
                () -> postSafe(worker(), () -> {
                    if (domSignalSession != session || !snapshotForeground(watch.snapshot)) return;
                    arm(new Signal(watch.snapshot.context, target.packageName(), target.activity(),
                            System.currentTimeMillis(), true));
                }));
    }

    private static Object proxyCall(PendingAssist pending, ReceiverBinder binder,
                                    Object proxy, String name, Object[] args) {
        try {
            return switch (name) {
                case "asBinder" -> binder;
                case "onHandleAssistData" -> {
                    if (args != null && args.length == 1 && args[0] instanceof Bundle data) {
                        pending.deliverData(data);
                    }
                    yield null;
                }
                case "onHandleAssistScreenshot" -> {
                    if (args != null && args.length == 1 && args[0] instanceof Bitmap bitmap) {
                        recycle(bitmap);
                    }
                    yield null;
                }
                case "toString" -> "MioPrivilegedAssistReceiver#" + pending.id;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> args != null && args.length == 1 && proxy == args[0];
                default -> null;
            };
        } catch (Throwable error) {
            pending.callbackFailed(error);
            return null;
        }
    }

    private static void onRequestTimeout(PendingAssist pending) {
        if (pending == null || pending.finished) return;
        Watch watch = pending.watch;
        if (watch.pending != pending) return;
        clearPending(pending);
        watch.misses++;
    }

    private static void onAssistData(PendingAssist pending, Bundle data) {
        if (pending == null || pending.finished) return;
        Watch watch = pending.watch;
        if (watch.pending != pending) return;
        clearPending(pending);
        if (!stillForeground(watch) || data == null) { finish(watch); return; }
        try {
            data.setClassLoader(AssistStructure.class.getClassLoader());
            AssistStructure structure = data.getParcelable("structure", AssistStructure.class);
            AssistContent assistContent = data.getParcelable("content", AssistContent.class);
            String content;
            if (watch.nativeRule != null && !watch.nativeRule.webView) {
                Rule.Target nativeTarget = watch.nativeRule.activityTarget(watch.snapshot.activity);
                if (nativeTarget == null) { success(); return; }
                var nativeResult = ColorOsNativeExtractor.extract(structure,
                        new ComponentName(watch.snapshot.packageName, watch.snapshot.activity),
                        nativeTarget.ignoreVisibility);
                content = nativeResult.text();
                HookHealth.state("native_reader", "原生控件树：" + nativeResult.status()
                        + "；ignoreVis=" + nativeTarget.ignoreVisibility
                        + "；缺少原版可见性元数据节点=" + nativeResult.fallbackVisibility());
            } else {
                content = AssistStructureExtractor.extract(structure, assistContent, watch.snapshot.label);
            }
            forwardCaptured(watch, content, assistContent);
        } catch (Throwable error) {
            fail("extract AssistStructure", error);
        }
    }

    /** Native source snapshots and legacy Assist use exactly the same rule/event path. */
    private static void forwardCaptured(Watch watch, String content, AssistContent assistContent) {
        try {
            if (content.isEmpty()) {
                watch.misses++;
                success();
                return;
            }

            Rule rule = watch.nativeRule;
            RuleRepository rules = repository;
            if (rule == null && rules != null) {
                rule = rules.matchPage(watch.snapshot.packageName,
                        watch.snapshot.label, content);
            }
            if (rule == null) {
                if (++watch.misses >= 4) finish(watch);
                success();
                return;
            }
            watch.misses = 0;
            Rule.Target target = watch.nativeRule == null
                    ? null : rule.activityTarget(watch.snapshot.activity);
            // AssistStructure has not supplied __route__. Configured filter_paths and
            // launch_path are NOT observations; substituting one corrupts PCR and page gates.
            String route = rule.webView ? "" : watch.snapshot.activity;
            PickupEvent event = rule.event(watch.snapshot.activity, rule.originId, route,
                    "", AssistStructureExtractor.webUri(assistContent), content, target,
                    rules == null ? Constants.BUILTIN_RULE_VERSION : rules.version());
            event.actualTaskLabel = watch.snapshot.label;
            event.fuzzyMatch = watch.nativeRule == null;
            if (!event.isStructurallyValid()) {
                success();
                return;
            }

            if (!watch.navigationSent && (!event.webView || event.pathObserved)) {
                event.navigationOnly = true;
                send(watch.snapshot.context, event);
                watch.navigationSent = true;
            }
            // Native i6.b -> g6.f has no extra Chinese keyword gate. The exact
            // XML Activity scope + original PCR decide, including nonstandard code labels.
            if (rule.webView && !AssistStructureExtractor.looksLikePickup(content)) {
                success();
                return;
            }

            int contentHash = content.hashCode();
            if (contentHash != watch.lastSentContentHash) {
                watch.lastSentContentHash = contentHash;
                event.navigationOnly = false;
                event.timestamp = System.currentTimeMillis();
                send(watch.snapshot.context, event);
                log("sent " + rule.brandName + " task=" + watch.snapshot.taskId
                        + " chars=" + content.length());
            }
            // Mini programs often replace an order in-place without changing Activity/task.
            // Keep this bounded foreground window alive so a new structured-text snapshot can
            // publish the new code. AICR performs event-level dedupe downstream.
            success();
        } catch (Throwable error) {
            fail("extract AssistStructure", error);
        }
    }

    private static void onCallbackFailure(PendingAssist pending, Throwable error) {
        if (pending == null || pending.finished) return;
        Watch watch = pending.watch;
        if (watch.pending == pending) clearPending(pending);
        fail("assist callback", error);
    }

    private static void send(Context context, PickupEvent event) {
        if (context == null || event == null) return;
        try {
            Intent intent = new Intent(Constants.ACTION_SYSTEM_EVENT)
                    .setComponent(new ComponentName(Constants.PKG_AICR,
                            Constants.AICR_WAKE_RECEIVER))
                    .putExtras(event.toBundle())
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            BroadcastOptions options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true);
            context.sendBroadcast(intent, null, options.toBundle());
        } catch (Throwable error) {
            fail("send AICR event", error);
        }
    }

    private static void clearPending(PendingAssist pending) {
        if (pending == null) return;
        pending.finished = true;
        Handler current = worker;
        Runnable timeout = pending.timeout;
        if (current != null && timeout != null) current.removeCallbacks(timeout);
        if (pending.watch.pending == pending) pending.watch.pending = null;
        pending.receiverProxy = null;
        pending.callbackBinder = null;
        Watch watch = pending.watch;
        if (current != null) scheduleDirtyFollowup(watch);
    }

    private static void cancelActive() {
        domSignalSession = null;
        Watch watch = activeWatch;
        if (watch != null) finish(watch);
    }

    private static void finish(Watch watch) {
        if (watch == null) return;
        watch.cancelled = true;
        if (activeWatch == watch) activeWatch = null;
        Handler current = worker;
        Runnable next = watch.next;
        if (current != null && next != null) current.removeCallbacks(next);
        if (current != null && watch.followup != null) current.removeCallbacks(watch.followup);
        PendingAssist pending = watch.pending;
        if (pending != null) clearPending(pending);
    }

    private static boolean isActive(Watch watch) {
        return watch != null && !watch.cancelled && activeWatch == watch;
    }

    private static Handler worker() {
        Handler current = worker;
        if (current != null) return current;
        synchronized (WORKER_LOCK) {
            current = worker;
            if (current == null) {
                HandlerThread thread = new HandlerThread(
                        "MioPickupAssist", Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
                current = new Handler(thread.getLooper());
                worker = current;
            }
            return current;
        }
    }

    private static void postSafe(Handler handler, Runnable runnable) {
        if (handler == null || runnable == null || circuitOpen) return;
        try {
            handler.post(() -> {
                if (circuitOpen) return;
                try {
                    runnable.run();
                } catch (Throwable error) {
                    fail("worker", error);
                }
            });
        } catch (Throwable error) {
            fail("post worker", error);
        }
    }

    private static void success() {
        CONSECUTIVE_FAILURES.set(0);
    }

    private static void fail(String stage, Throwable error) {
        int count = CONSECUTIVE_FAILURES.incrementAndGet();
        if (count >= MAX_CONSECUTIVE_FAILURES && !circuitOpen) {
            circuitOpen = true;
            try {
                Handler current = worker;
                if (current != null) current.post(PrivilegedAssistHook::cancelActive);
            } catch (Throwable ignored) {
            }
            log("safety circuit opened after " + count
                    + " consecutive failures; disabled until this app process restarts");
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastErrorLogAt < 30_000L) return;
        lastErrorLogAt = now;
        log(stage + " failed: " + error);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof InvocationTargetException
                && ((InvocationTargetException) current).getCause() != null) {
            current = ((InvocationTargetException) current).getCause();
        }
        return current;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap == null) return;
        try {
            if (!bitmap.isRecycled()) bitmap.recycle();
        } catch (Throwable ignored) {
        }
    }

    private static String simpleName(String value) {
        int index = value == null ? -1 : value.lastIndexOf('.');
        return index < 0 ? PickupEvent.clean(value) : value.substring(index + 1);
    }

    private static void log(String message) {
        try {
            XposedBridge.log("AutoPickupIsland/Assist: " + message);
        } catch (Throwable ignored) {
        }
    }

    private record Signal(Context context, String packageName, String activity,
                          long observedAt, boolean contentChange) {
    }

    private record TaskSnapshot(Context context, int taskId, String packageName,
                                String activity, String label) {
    }

    private static final class Watch {
        final TaskSnapshot snapshot;
        final Rule nativeRule;
        final long armedAt;
        final boolean singleShot;
        int round;
        int misses;
        int lastSentContentHash;
        boolean navigationSent;
        boolean nativeFallback;
        volatile boolean cancelled;
        volatile boolean domBusy;
        io.github.mio.autopickupisland.coloros.UiAgentPageSignal pageSignal;
        String lastDomIdentity = "", lastDomStatus = "";
        boolean contentDirty;
        Runnable next;
        Runnable followup;
        PendingAssist pending;

        Watch(TaskSnapshot snapshot, Rule nativeRule, long armedAt, boolean singleShot) {
            this.snapshot = snapshot;
            this.nativeRule = nativeRule;
            this.armedAt = armedAt;
            this.singleShot = singleShot;
        }
    }

    private static final class PendingAssist {
        final Watch watch;
        final long id;
        volatile boolean finished;
        volatile Runnable timeout;
        volatile ReceiverBinder callbackBinder;
        volatile Object receiverProxy;

        PendingAssist(Watch watch, long id) {
            this.watch = watch;
            this.id = id;
        }

        void deliverData(Bundle data) {
            if (finished) return;
            Handler current = worker;
            if (current == null) return;
            postSafe(current, () -> onAssistData(this, data));
        }

        void callbackFailed(Throwable error) {
            if (finished) return;
            Handler current = worker;
            if (current == null) return;
            postSafe(current, () -> onCallbackFailure(this, error));
        }
    }

    /** Local Binder implementation matching Android 17's IAssistDataReceiver.Stub. */
    private static final class ReceiverBinder extends Binder {
        private final PendingAssist owner;

        ReceiverBinder(PendingAssist owner) {
            this.owner = owner;
            attachInterface(null, ASSIST_RECEIVER_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                if (code >= IBinder.FIRST_CALL_TRANSACTION
                        && code <= IBinder.LAST_CALL_TRANSACTION) {
                    data.enforceInterface(ASSIST_RECEIVER_DESCRIPTOR);
                }
                if (code == TRANSACTION_ASSIST_DATA) {
                    Bundle bundle = data.readTypedObject(Bundle.CREATOR);
                    data.enforceNoDataAvail();
                    owner.deliverData(bundle);
                    return true;
                }
                if (code == TRANSACTION_ASSIST_SCREENSHOT) {
                    Bitmap bitmap = data.readTypedObject(Bitmap.CREATOR);
                    data.enforceNoDataAvail();
                    recycle(bitmap);
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable error) {
                owner.callbackFailed(error);
                return true;
            }
        }
    }
}
