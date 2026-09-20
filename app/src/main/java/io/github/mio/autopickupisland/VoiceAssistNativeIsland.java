package io.github.mio.autopickupisland;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.BroadcastOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.io.InputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Relays a recognized pickup scene into Super XiaoAi's native memory-island pipeline.
 *
 * The public Focus templates cannot render the 130 dp pickup card. VoiceAssist already
 * contains the exact layout and behavior in MemorySceneIslandManager, so this bridge
 * invokes that manager inside VoiceAssist and only decorates its image with the ColorOS
 * animated WebP. The source application is never hooked.
 */
final class VoiceAssistNativeIsland {
    private static final String AGENT_CLASS = "com.xiaomi.ai.api.Agent$SuperIsland";
    private static final long RELAY_WINDOW_MS = 4_500L;
    private static final String EXTRA_DEADLINE = "mio.native.deadline.elapsed";
    private static final String RECEIPT_HOOK_KEY = "mio.native.receipt";
    private static final String EXTRA_WAITING = "mio.native.waiting";
    private static final String EXTRA_ORDER_STATE = "mio.native.orderState";
    private static final int RESULT_SUPPRESSED = Activity.RESULT_FIRST_USER + 1;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final Map<Integer, Decoration> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger OUTSTANDING = new AtomicInteger();
    private static final int MAX_OUTSTANDING = 128;

    enum Delivery { POSTED, REJECTED, UNKNOWN, SUPPRESSED }

    private static volatile ClassLoader voiceLoader;
    private static volatile Class<?> managerType;
    private static volatile Object managerInstance;
    private static volatile boolean notifyHookReady;

    private VoiceAssistNativeIsland() {
    }

    /** Called from AICR's one-shot recognition worker. */
    static Delivery request(Context context, PickupEvent event, ColorOsRecognizer.Result result) {
        if (context == null || event == null || result == null
                || (!result.found() && !result.waitingForCode(event))) return Delivery.REJECTED;
        try {
            PendingIntent open = IslandPublisher.createOpenPendingIntent(context, event);
            if (open == null) return Delivery.REJECTED;

            String product = IslandPublisher.displayProduct(event, result);
            String detail = IslandPublisher.displayDetail(event, result,
                    event.brand.isEmpty() ? "取餐提醒" : event.brand, product);
            String model = IslandPublisher.modelAsset(event, result);

            Intent request = new Intent(Constants.ACTION_NATIVE_PUBLISH)
                    .setComponent(new ComponentName(Constants.PKG_VOICE_ASSIST,
                            Constants.VOICE_ASSIST_WAKE_RECEIVER))
                    .setIdentifier("mio-native-pickup-" + event.id())
                    .putExtras(event.toBundle())
                    .putExtra(Constants.EXTRA_CODE, result.waitingForCode(event) ? NativePickupOwnership.WAITING_LABEL : result.code)
                    .putExtra(EXTRA_WAITING, result.waitingForCode(event))
                    .putExtra(EXTRA_ORDER_STATE, result.orderState)
                    .putExtra(Constants.EXTRA_PRODUCT, product)
                    .putExtra(Constants.EXTRA_DETAIL, detail)
                    .putExtra(Constants.EXTRA_MODEL, model)
                    .putExtra(Constants.EXTRA_ENGINE, result.engine)
                    .putExtra(Constants.EXTRA_CONTENT_INTENT, open)
                    .putExtra(EXTRA_DEADLINE, SystemClock.elapsedRealtime() + RELAY_WINDOW_MS)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                            | Intent.FLAG_RECEIVER_FOREGROUND);

            CountDownLatch delivered = new CountDownLatch(1);
            AtomicInteger resultCode = new AtomicInteger(Activity.RESULT_CANCELED);
            BroadcastReceiver completion = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    try {
                        resultCode.set(getResultCode());
                    } finally {
                        delivered.countDown();
                    }
                }
            };
            BroadcastOptions options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE);
            context.sendOrderedBroadcast(request, null, options.toBundle(), completion,
                    new Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null);
            if (!delivered.await(6, TimeUnit.SECONDS)) {
                log("native relay timed out; delivery unknown, no duplicate fallback");
                return Delivery.UNKNOWN;
            }
            return resultCode.get() == RESULT_SUPPRESSED ? Delivery.SUPPRESSED
                    : resultCode.get() == Activity.RESULT_OK ? Delivery.POSTED
                    : resultCode.get() == Activity.RESULT_CANCELED ? Delivery.REJECTED : Delivery.UNKNOWN;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Delivery.UNKNOWN;
        } catch (Throwable error) {
            log("native relay failed: " + error);
            return Delivery.UNKNOWN;
        }
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!Constants.PKG_VOICE_ASSIST.equals(lpparam.processName)
                || !INSTALLED.compareAndSet(false, true)) return;
        voiceLoader = lpparam.classLoader;

        try {
            XposedHelpers.findAndHookMethod(android.app.Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.args[0] instanceof Context context)) return;
                            try {
                                new Thread(() -> {
                                    try { managerType = VoiceTargetResolver.find(context, voiceLoader); }
                                    catch (Throwable error) { HookHealth.state("manager", "定位失败：" + error.getClass().getSimpleName()); }
                                }, "PickupVoiceTargetScan").start();
                            } catch (Throwable ignored) { HookHealth.state("manager", "定位线程启动失败"); }
                        }
                    });
        } catch (Throwable error) {
            log("manager constructor probe skipped: " + error);
        }

        try {
            Class<?> receiverType = XposedHelpers.findClassIfExists(
                    Constants.VOICE_ASSIST_WAKE_RECEIVER, voiceLoader);
            if (receiverType == null) throw new ClassNotFoundException(
                    Constants.VOICE_ASSIST_WAKE_RECEIVER);
            XposedBridge.hookAllMethods(receiverType, "onReceive", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length >= 2
                                && param.thisObject instanceof BroadcastReceiver receiver
                                && param.args[0] instanceof Context context
                                && param.args[1] instanceof Intent intent
                                && Constants.ACTION_CLEAR_CLOSED.equals(intent.getAction())) {
                            param.setResult(null);
                            if (!BroadcastVerifier.isPackage(receiver, context, Constants.MODULE_PACKAGE)) return;
                            BroadcastReceiver.PendingResult pending = receiver.goAsync();
                            if (pending == null) return;
                            try {
                                new Thread(() -> {
                                    try {
                                        int count = NativePickupOwnership.clearConfirmed(context);
                                        pending.setResultData(Integer.toString(count));
                                        pending.setResultCode(Activity.RESULT_OK);
                                    } catch (Throwable ignored) {
                                        pending.setResultCode(Activity.RESULT_CANCELED);
                                    } finally { pending.finish(); }
                                }, "PickupClearHistory").start();
                            } catch (Throwable ignored) { pending.finish(); }
                            return;
                        }
                        if (param.args.length >= 2 && param.args[0] instanceof Context context
                                && param.args[1] instanceof Intent intent
                                && NativeWechatNavigation.handle(context, intent, voiceLoader)) {
                            param.setResult(null);
                            return;
                        }
                    } catch (Throwable ignored) { }
                    if (param.args.length < 2
                            || !(param.thisObject instanceof BroadcastReceiver receiver)
                            || !(param.args[0] instanceof Context context)
                            || !(param.args[1] instanceof Intent intent)
                            || !Constants.ACTION_NATIVE_PUBLISH.equals(intent.getAction())) {
                        return;
                    }

                    boolean asyncOwned = false;
                    BroadcastReceiver.PendingResult asyncResult = null;
                    try {
                        if (!BroadcastVerifier.isPackage(receiver, context, Constants.PKG_AICR)) {
                            log("rejected native relay from uid=" + receiver.getSentFromUid());
                            return;
                        }
                        Context application = context.getApplicationContext();
                        voiceContext = application == null ? context : application;
                        PickupEvent event = PickupEvent.fromBundle(intent.getExtras());
                        boolean waiting = intent.getBooleanExtra(EXTRA_WAITING, false);
                        String code = waiting && event != null && event.needWaitingStatus
                                && (!event.webView || event.pathObserved)
                                && NativePickupOwnership.WAITING_LABEL.equals(intent.getStringExtra(Constants.EXTRA_CODE))
                                ? NativePickupOwnership.WAITING_LABEL : NativePickupCode.accept(
                                        intent.getStringExtra(Constants.EXTRA_CODE), event == null ? "" : event.tagAppName);
                        String product = cleanLine(intent.getStringExtra(Constants.EXTRA_PRODUCT), 48);
                        String detail = cleanLine(intent.getStringExtra(Constants.EXTRA_DETAIL), 72);
                        String model = safeModel(intent.getStringExtra(Constants.EXTRA_MODEL));
                        PendingIntent open = intent.getParcelableExtra(
                                Constants.EXTRA_CONTENT_INTENT, PendingIntent.class);
                        if (!notifyHookReady || !validEvent(event, code) || !trustedPendingIntent(open, event)) return;
                        long now = SystemClock.elapsedRealtime();
                        long deadline = intent.getLongExtra(EXTRA_DEADLINE, 0L);
                        if (deadline <= now || deadline - now > RELAY_WINDOW_MS) return;
                        CloudNameEnrichment.candidate(event, code);
                        BroadcastReceiver.PendingResult pendingResult = receiver.goAsync();
                        if (pendingResult == null) return;
                        asyncResult = pendingResult;
                        asyncOwned = true;
                        if (NativePickupOwnership.suppress(voiceContext, event, NativePickupOwnership.semantic(code, waiting))) {
                            if (!waiting) cleanupShowingDuplicates(event, code);
                            pendingResult.setResultCode(RESULT_SUPPRESSED); pendingResult.finish();
                        } else publishOfficial(context, event, code, product, detail, model, open,
                                deadline, pendingResult, intent.getIntExtra(EXTRA_ORDER_STATE, 2));
                    } catch (Throwable error) {
                        log("native receiver failed safely: " + error);
                        if (asyncResult != null) {
                            try { asyncResult.setResultCode(Activity.RESULT_FIRST_USER); }
                            catch (Throwable ignored) { }
                            finally { try { asyncResult.finish(); } catch (Throwable ignored) { } }
                        }
                    } finally {
                        try {
                            if (!asyncOwned && receiver.isOrderedBroadcast()) {
                                receiver.setResultCode(Activity.RESULT_CANCELED);
                            }
                        } catch (Throwable ignored) {
                        }
                        // LaunchMainPidReceiver is intentionally a no-op. Do not pass our
                        // private action farther into VoiceAssist code on future builds.
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable error) {
            log("wake receiver hook unavailable: " + error);
        }

        try {
            XC_MethodHook decorate = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length != 2 || !(param.args[0] instanceof Integer id)
                                || !(param.args[1] instanceof Notification notification)) return;
                        Decoration decoration = PENDING.get(id);
                        if (decoration == null || !matches(notification, decoration)) return;
                        if (voiceContext != null && NativePickupOwnership.suppress(voiceContext,
                                decoration.event, decoration.semanticCode)) {
                            PENDING.remove(id, decoration);
                            param.setResult(null);
                            decoration.receipt.failed();
                            decoration.finish(RESULT_SUPPRESSED);
                            decoration.release();
                            return;
                        }
                        if (!decoration.receipt.canPost(SystemClock.elapsedRealtime())) {
                            PENDING.remove(id, decoration);
                            // This ID was supplied by our own original-manager invocation.
                            // Never let its expired coroutine publish after fallback/retry.
                            param.setResult(null);
                            decoration.finish(Activity.RESULT_FIRST_USER);
                            decoration.release();
                            return;
                        }
                        param.setObjectExtra(RECEIPT_HOOK_KEY, decoration);
                        decorateNativeNotification(notification, id, decoration);
                    } catch (Throwable error) {
                        log("native card decoration skipped: " + error);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object extra = param.getObjectExtra(RECEIPT_HOOK_KEY);
                        if (!(extra instanceof Decoration decoration)) return;
                        if (param.args[0] instanceof Integer id) PENDING.remove(id, decoration);
                        if (param.hasThrowable()) {
                            decoration.receipt.failed();
                            decoration.finish(Activity.RESULT_FIRST_USER);
                        } else {
                            decoration.receipt.posted(SystemClock.elapsedRealtime());
                            if (param.args[0] instanceof Integer id) retireReplacedCards(id, decoration);
                            decoration.finish(Activity.RESULT_OK);
                            if (param.args[0] instanceof Integer id && voiceContext != null && cloudCancellationReady
                                    && decoration.cloudStarted.compareAndSet(false, true)) {
                                CloudNameEnrichment.offer(voiceContext, decoration.event, decoration.code,
                                        decoration.product, decoration.orderState, id, decoration.instance,
                                        name -> updateCloudName(id, decoration, name));
                            }
                        }
                        decoration.release();
                    } catch (Throwable error) { log("native receipt failed safely: " + error.getClass().getSimpleName()); }
                }
            };
            // OS4 original d$h calls this exact Android framework overload. Hooking all
            // overloads double-counts nested notify->notifyAsUser calls and other publishers.
            XposedHelpers.findAndHookMethod(NotificationManager.class, "notify", int.class,
                    Notification.class, decorate);
            notifyHookReady = true;
        } catch (Throwable error) {
            log("notification decoration hook unavailable: " + error);
        }

        // Observe, never replace, Xiaomi's original confirmation behavior.
        try {
            Class<?> action = XposedHelpers.findClassIfExists(
                    "com.xiaomi.voiceassistant.memory.island.MemorySceneActionReceiver", voiceLoader);
            if (action != null) XposedBridge.hookAllMethods(action, "onReceive", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length == 2 && param.args[0] instanceof Context context
                                && param.args[1] instanceof Intent intent
                                && "com.xiaomi.voiceassistant.ACTION_MEMORY_SCENE_BUTTON".equals(intent.getAction())) {
                            CloudNameEnrichment.cancel(intent.getIntExtra("notification_id", -1));
                            param.setObjectExtra("mio.confirm.owned", NativePickupOwnership.byId(context,
                                    intent.getIntExtra("notification_id", -1)));
                        }
                    } catch (Throwable ignored) { }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!param.hasThrowable() && param.args[0] instanceof Context context
                                && param.getObjectExtra("mio.confirm.owned") instanceof NativePickupOwnership.Owned owned)
                            NativePickupOwnership.confirmed(context, owned);
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable error) { log("owned confirmation tracking unavailable"); }

        // Invalidate the one in-flight name update BEFORE a cancellation is
        // enqueued at NMS, which may briefly still report the old active card.
        try {
            XposedHelpers.findAndHookMethod(NotificationManager.class, "cancel", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try { if (p.args[0] instanceof Integer id) CloudNameEnrichment.cancel(id); } catch (Throwable ignored) { }
                }
            });
            XposedHelpers.findAndHookMethod(NotificationManager.class, "cancel", String.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try { if (p.args[0] == null && p.args[1] instanceof Integer id) CloudNameEnrichment.cancel(id); } catch (Throwable ignored) { }
                }
            });
            XposedHelpers.findAndHookMethod(NotificationManager.class, "cancelAll", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { try { CloudNameEnrichment.cancelAll(); } catch (Throwable ignored) { } }
            });
            cloudCancellationReady = true;
        } catch (Throwable ignored) { log("cloud name cancellation guards unavailable; cloud path disabled"); cloudCancellationReady = false; }

        log("official MemoryScene island bridge installed");
        HookHealth.state("notification", notifyHookReady ? "原生通知 Hook 已安装，管理器定位状态另见下项" : "通知 Hook 未就绪");
    }

    private static void publishOfficial(Context rawContext, PickupEvent event, String code,
                                           String product, String detail, String model,
                                           PendingIntent open, long deadline,
                                           BroadcastReceiver.PendingResult pendingResult, int orderState) {
        HookHealth.callback("原生卡片发布请求已到达");
        Decoration decoration = new Decoration(event, code, product, detail, model,
                sceneFor(event), open, deadline, pendingResult, orderState);
        if (OUTSTANDING.incrementAndGet() > MAX_OUTSTANDING) {
            OUTSTANDING.decrementAndGet();
            decoration.finish(Activity.RESULT_CANCELED);
            return;
        }
        decoration.leased = true;
        boolean invocationStarted = false;
        try {
            Handler timeoutHandler = new Handler(Looper.getMainLooper());
            if (!timeoutHandler.postDelayed(() -> {
                decoration.receipt.state(SystemClock.elapsedRealtime());
                decoration.finish(Activity.RESULT_FIRST_USER);
                // Retain expired ID reservations until their late coroutine arrives.
                // The outstanding cap bounds these tombstones without allowing an old
                // job to escape the deadline guard after an arbitrary eviction TTL.
            }, Math.max(1L, deadline - SystemClock.elapsedRealtime())))
                throw new IllegalStateException("relay timeout handler unavailable");
            Context application = rawContext.getApplicationContext();
            Context context = application == null ? rawContext : application;
            Class<?> agentType = XposedHelpers.findClassIfExists(AGENT_CLASS, voiceLoader);
            Object manager = resolveManager(context);
            if (agentType == null || manager == null) {
                log("official manager unavailable");
                decoration.receipt.failed();
                decoration.finish(Activity.RESULT_CANCELED);
                decoration.release();
                return;
            }

            Object island = agentType.getDeclaredConstructor().newInstance();
            invokeSetter(island, "setTitle", code);
            invokeSetter(island, "setContent", product.isEmpty()
                    ? (event.brand.isEmpty() ? "取餐订单" : event.brand + "订单") : product);
            invokeSetter(island, "setSubtitle", detail);
            String scene = sceneFor(event);
            invokeSetter(island, "setScene", scene);
            if (!event.brand.isEmpty()) invokeSetter(island, "setBrandName", event.brand);
            if (!product.isEmpty()) {
                invokeSetter(island, "setProductNames", Collections.singletonList(product));
            }

            Method notifyMethod = findNotifyMethod(manager.getClass(), agentType);
            if (notifyMethod == null) {
                log("official notify signature not found");
                decoration.receipt.failed();
                decoration.finish(Activity.RESULT_CANCELED);
                decoration.release();
                return;
            }

            // Official d$h invokes Function2<Integer, Boolean, PendingIntent> twice with
            // its generated notification ID. Returning null preserves the previous null
            // callback's official PendingIntent behavior; only record the invocation ID.
            Class<?> callbackType = notifyMethod.getParameterTypes()[3];
            Object callback = Proxy.newProxyInstance(voiceLoader, new Class<?>[]{callbackType},
                    (proxy, method, args) -> {
                        try {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return args != null && args.length == 1 && proxy == args[0];
                            if ("toString".equals(method.getName())) return "MioNativeIdCallback";
                            if ("invoke".equals(method.getName()) && args != null && args.length == 2
                                    && args[0] instanceof Integer id && args[1] instanceof Boolean) {
                                PENDING.putIfAbsent(id, decoration);
                            }
                        } catch (Throwable error) {
                            decoration.receipt.failed();
                            decoration.finish(Activity.RESULT_FIRST_USER);
                        }
                        return null;
                    });
            notifyMethod.setAccessible(true);
            invocationStarted = true;
            notifyMethod.invoke(manager, context, island, open, callback, null);
            log("scheduled official " + scene + " card via " + notifyMethod.getName());
        } catch (Throwable error) {
            log("official manager invocation failed: " + rootMessage(error));
            decoration.receipt.failed();
            decoration.finish(invocationStarted ? Activity.RESULT_FIRST_USER : Activity.RESULT_CANCELED);
            if (!invocationStarted) decoration.release();
        }
    }

    private static Object resolveManager(Context context) {
        Object cached = managerInstance;
        Class<?> expected = managerType;
        if (cached != null && (expected == null || expected.isInstance(cached))) return cached;
        if (expected == null) return null;

        try {
            Object application = context.getApplicationContext();
            if (application == null) application = context;
            Object component = invokeNoArg(application, "generatedComponent");
            Object found = findReturnedInstance(component, expected);
            if (found != null) {
                managerInstance = found;
                return found;
            }
        } catch (Throwable ignored) {
        }

        return managerInstance;
    }

    private static Object findReturnedInstance(Object owner, Class<?> expected) {
        if (owner == null || expected == null) return null;
        if (expected.isInstance(owner)) return owner;
        for (Method method : owner.getClass().getMethods()) {
            try {
                if (method.getParameterCount() != 0
                        || !expected.isAssignableFrom(method.getReturnType())) continue;
                method.setAccessible(true);
                Object result = method.invoke(owner);
                if (expected.isInstance(result)) return result;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object owner, String name) throws Exception {
        return SafeReflection.noArg(owner, name);
    }

    static Method findNotifyMethod(Class<?> type, Class<?> agentType) {
        Method found = null;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 5 || Modifier.isStatic(method.getModifiers())) continue;
                if (!Context.class.isAssignableFrom(parameters[0])
                        || !parameters[1].isAssignableFrom(agentType)
                        || parameters[2] != PendingIntent.class) continue;
                String fourth = parameters[3].getName();
                String fifth = parameters[4].getName();
                if (fourth.endsWith("Function2") && fifth.endsWith("Function0")) {
                    if (found != null) return null; // Ambiguous OS4 ABI: do not guess a publisher.
                    found = method;
                }
            }
        }
        return found;
    }

    private static void invokeSetter(Object target, String name, Object value) throws Exception {
        Method candidate = null;
        for (Method method : target.getClass().getMethods()) {
            if (name.equals(method.getName()) && method.getParameterCount() == 1) {
                candidate = method;
                break;
            }
        }
        if (candidate == null) throw new NoSuchMethodException(name);
        candidate.invoke(target, value);
    }

    private static boolean matches(Notification notification, Decoration decoration) {
        Bundle extras = notification == null ? null : notification.extras;
        return extras != null && decoration.code.equals(PickupEvent.clean(String.valueOf(
                extras.getCharSequence(Notification.EXTRA_TITLE, ""))))
                && looksLikeMemoryScene(extras.getString("miui.focus.param.custom", ""));
    }

    private static void decorateNativeNotification(Notification notification, int notificationId,
                                                   Decoration decoration) {
        Bundle extras = notification.extras;
        if (extras == null) return;
        String custom = extras.getString("miui.focus.param.custom", "");
        if (!looksLikeMemoryScene(custom)) return;
        notification.contentIntent = decoration.open;
        extras.putBoolean("mio.auto_pickup", true);
        extras.putBoolean("mio.voiceassist.native_memory_scene", true);
        NativePickupOwnership.mark(notification, decoration.event, decoration.semanticCode, decoration.instance);
        extras.putString("mio.pickup.appId", decoration.event.appId);
        extras.putString("mio.pickup.path", decoration.event.openPath());
        extras.putString("mio.pickup.scene", decoration.scene);

        try {
            JSONObject root = new JSONObject(custom);
            root.put("enableFloat", true);
            root.put("islandFirstFloat", true);
            extras.putString("miui.focus.param.custom", root.toString());
        } catch (Throwable ignored) {
        }

        Icon animated = animatedIcon(decoration.model);
        bindContentClicksAndIcon(notification, decoration.open, animated);
        if (animated != null) {
            Bundle pictures = extras.getBundle("miui.focus.pics");
            if (pictures == null) pictures = new Bundle();
            pictures.putParcelable(pictureKey(decoration.scene), animated);
            extras.putBundle("miui.focus.pics", pictures);
        }

        // Keep Xiaomi's MemoryScene notification lifecycle, tiny island, focus whitelist and
        // dismissal receiver. Only our marked automatic expanded views are replaced. If the
        // official confirmation PendingIntent cannot be resolved, fail closed and retain the
        // complete Xiaomi card instead of installing a visually working but unsafe button.
        PendingIntent confirm = resolveOfficialConfirmPendingIntent(notificationId);
        if (confirm != null) {
            try {
                RemoteViews expanded = buildColorOsExpandedView(decoration, animated, confirm);
                extras.putParcelable("miui.focus.rv", expanded);
                extras.putParcelable("miui.focus.rvNight", expanded);
                extras.putParcelable("miui.focus.rv.island.expand", expanded);
                log("installed ColorOS expanded layout with Xiaomi confirm action");
            } catch (Throwable error) {
                log("ColorOS expanded layout skipped safely: " + rootMessage(error));
            }
        } else {
            log("official confirm action unavailable; retained Xiaomi expanded card");
        }
        enableFloat(notification);
        log("decorated official MemoryScene card; tiny island and lifecycle untouched");
    }

    private static void retireReplacedCards(int newId, Decoration decoration) {
        retireReplacedCards(newId, decoration.instance, 0);
    }

    private static volatile boolean cloudCancellationReady;
    // This code executes in VoiceAssist, whose permission is checked below;
    // the module APK intentionally does not request notification permission.
    @SuppressLint("NotificationPermission")
    private static boolean updateCloudName(int id, Decoration decoration, String name) throws Exception {
        if (!cloudCancellationReady || voiceContext == null || Looper.myLooper() != Looper.getMainLooper()) return false;
        if (voiceContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        var own = NativePickupOwnership.byId(voiceContext, id);
        if (own == null || !decoration.instance.equals(own.instance()) || !decoration.semanticCode.equals(own.code())
                || !NativePickupOwnership.identity(decoration.event).equals(own.identity())) return false;
        for (var other : NativePickupOwnership.active(voiceContext)) {
            if (other.id() != id && other.identity().equals(own.identity()) && other.postTime() >= own.postTime()) return false;
        }
        NotificationManager manager = voiceContext.getSystemService(NotificationManager.class);
        if (manager == null) return false;
        for (var entry : manager.getActiveNotifications()) {
            if (entry.getId() != id || entry.getTag() != null || !Constants.PKG_VOICE_ASSIST.equals(entry.getPackageName())) continue;
            Notification source = entry.getNotification();
            Notification updated = CloudNameNotification.copy(source, decoration.instance, name);
            if (updated == null) return false;
            // Same id/instance, original confirmation/open/delete PendingIntents,
            // code, model, tint and native lifecycle. No original-manager re-publish.
            manager.notify(id, updated);
            return true;
        }
        return false;
    }

    private static void cleanupShowingDuplicates(PickupEvent event, String code) {
        try {
            String identity = NativePickupOwnership.identity(event);
            NativePickupOwnership.Owned newest = null;
            for (var card : NativePickupOwnership.active(voiceContext)) {
                if (identity.equals(card.identity()) && code.equals(card.code())
                        && (newest == null || card.postTime() > newest.postTime())) newest = card;
            }
            if (newest != null) retireReplacedCards(newest.id(), newest.instance(), 0);
        } catch (Throwable ignored) { log("duplicate reconciliation skipped safely"); }
    }

    private static void retireReplacedCards(int newId, String instance, int attempt) {
        try {
            Context context = voiceContext;
            if (context == null) return;
            NativePickupOwnership.Owned posted = NativePickupOwnership.byId(context, newId);
            if (posted == null) {
                // notify() acknowledges enqueue, not visibility in active notifications.
                if (attempt < 3) new Handler(Looper.getMainLooper()).postDelayed(
                        () -> retireReplacedCards(newId, instance, attempt + 1),
                        new long[]{250L, 750L, 1_500L}[attempt]);
                return;
            }
            if (!instance.equals(posted.instance())) return;
            Object manager = resolveManager(context);
            if (manager == null) return;
            Method cancel = manager.getClass().getMethod("cancel", Context.class, int.class);
            for (var previous : NativePickupOwnership.active(context)) {
                if (!NativePickupOwnership.replaces(posted, previous)) continue;
                var stillPosted = NativePickupOwnership.byId(context, newId);
                if (stillPosted == null || !posted.instance().equals(stillPosted.instance())) return;
                var verified = NativePickupOwnership.byId(context, previous.id());
                if (verified != null && previous.instance().equals(verified.instance())
                        && NativePickupOwnership.replaces(stillPosted, verified))
                    cancel.invoke(manager, context, previous.id());
            }
        } catch (Throwable ignored) { log("owned replacement cleanup deferred; unrelated cards untouched"); }
    }

    private static PendingIntent resolveOfficialConfirmPendingIntent(int notificationId) {
        Context context = voiceContext;
        if (context == null || notificationId == Integer.MIN_VALUE) return null;
        try {
            Object manager = resolveManager(context);
            if (manager == null) return null;
            Method method = findConfirmActionMethod(manager.getClass());
            if (method == null) return null;
            method.setAccessible(true);
            Object result = method.invoke(manager, context, notificationId);
            if (result instanceof PendingIntent pendingIntent
                    && Constants.PKG_VOICE_ASSIST.equals(pendingIntent.getCreatorPackage())) {
                return pendingIntent;
            }
        } catch (Throwable error) {
            log("official confirm action lookup failed: " + rootMessage(error));
        }
        return null;
    }

    private static Method findConfirmActionMethod(Class<?> type) {
        Method fallback = null;
        int fallbackCount = 0;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers())
                        || !PendingIntent.class.isAssignableFrom(method.getReturnType())
                        || parameters.length != 2
                        || !Context.class.isAssignableFrom(parameters[0])
                        || parameters[1] != int.class) {
                    continue;
                }
                // No short-name preference: only a unique PendingIntent(Context,int) candidate.
                fallback = method;
                fallbackCount++;
            }
        }
        return fallbackCount == 1 ? fallback : null;
    }

    private static RemoteViews buildColorOsExpandedView(Decoration decoration, Icon animated,
                                                         PendingIntent confirm) throws Exception {
        Context context = voiceContext;
        if (context == null) throw new IllegalStateException("VoiceAssist context unavailable");
        RemoteViews views = new RemoteViews(Constants.MODULE_PACKAGE,
                R.layout.coloros_pickup_focus);

        String product = decoration.product;
        if (product.isEmpty()) product = decoration.event.brand;
        if (product.isEmpty()) product = "取餐订单";
        views.setTextViewText(R.id.coloros_pickup_product, product);
        views.setTextViewText(R.id.coloros_pickup_status, "查看制作进度");
        views.setTextViewText(R.id.coloros_pickup_code, decoration.code);
        views.setTextViewTextSize(R.id.coloros_pickup_code, TypedValue.COMPLEX_UNIT_SP,
                containsHan(decoration.code) ? 24f : 28f);
        views.setImageViewBitmap(R.id.coloros_pickup_divider, createDotLine(context));

        int accent = Color.parseColor(PickupEvent.color(
                decoration.event.pickupButtonColor, "#3482FF"));
        int tint = Color.parseColor(PickupEvent.color(decoration.event.cardColor, "#3A3A3A"));
        int tintAlpha = Math.max(0, Math.min(255,
                Math.round(decoration.event.cardAlpha * 255f)));
        views.setTextColor(R.id.coloros_pickup_code, accent);
        views.setInt(R.id.coloros_pickup_action_bg, "setColorFilter", accent);
        views.setInt(R.id.coloros_pickup_card_tint, "setColorFilter", tint);
        views.setInt(R.id.coloros_pickup_card_tint, "setImageAlpha", tintAlpha);

        if (animated != null) {
            views.setImageViewIcon(R.id.coloros_pickup_model, animated);
        } else {
            views.setViewVisibility(R.id.coloros_pickup_model, View.INVISIBLE);
        }
        Bitmap sticker = loadSticker(decoration.event.sticker);
        if (sticker != null) {
            views.setImageViewBitmap(R.id.coloros_pickup_sticker, sticker);
        } else {
            views.setViewVisibility(R.id.coloros_pickup_sticker, View.GONE);
        }

        views.setOnClickPendingIntent(R.id.coloros_pickup_root, decoration.open);
        views.setOnClickPendingIntent(R.id.coloros_pickup_model_area, decoration.open);
        views.setOnClickPendingIntent(R.id.coloros_pickup_content, decoration.open);
        views.setOnClickPendingIntent(R.id.coloros_pickup_action_container, confirm);
        views.setOnClickPendingIntent(R.id.coloros_pickup_action_text, confirm);
        return views;
    }

    /** Mirrors ColorOS DotLineView: 3dp circles, 6dp center advance, #1FFFFFFF. */
    private static Bitmap createDotLine(Context context) {
        float density = Math.max(1f, context.getResources().getDisplayMetrics().density);
        int densityDpi = context.getResources().getDisplayMetrics().densityDpi;
        int width = Math.max(1, Math.round(260f * density));
        int height = Math.max(1, Math.round(3f * density));
        float radius = 1.5f * density;
        float advance = 6f * density;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(densityDpi);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.argb(0x1F, 0xFF, 0xFF, 0xFF));
        float centerY = height / 2f;
        for (float centerX = radius; centerX < width + radius; centerX += advance) {
            canvas.drawCircle(centerX, centerY, radius, paint);
        }
        return bitmap;
    }

    private static boolean containsHan(String value) {
        if (value == null) return false;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) return true;
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static Bitmap loadSticker(String value) {
        Context context = voiceContext;
        String asset = PickupEvent.clean(value).replace('\\', '/');
        if (context == null || !asset.matches("stickers/[a-z0-9_]{1,84}\\.png")) return null;
        try {
            Context module = ModuleAccess.context(context);
            try (InputStream input = module.getAssets().open("coloros/pickupcode/" + asset)) {
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                if (bitmap == null || bitmap.getWidth() > 1024 || bitmap.getHeight() > 1024
                        || bitmap.getAllocationByteCount() > 2 * 1024 * 1024) {
                    if (bitmap != null) bitmap.recycle();
                    return null;
                }
                return bitmap;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Used by the manual three-finger repair path; never touches action views. */
    static void bindContentClicks(Context context, Notification notification, PendingIntent open) {
        if (context == null || notification == null || open == null) return;
        Context application = context.getApplicationContext();
        voiceContext = application == null ? context : application;
        notification.contentIntent = open;
        bindContentClicksAndIcon(notification, open, null);
    }

    private static void bindContentClicksAndIcon(Notification notification, PendingIntent open,
                                                 Icon icon) {
        Bundle extras = notification.extras;
        if (extras == null || open == null) return;
        Context context = voiceContext;
        if (context == null) return;
        Resources resources = context.getResources();
        editRemoteView(extras, "miui.focus.rv", resources,
                "memory_scene_focus_icon", "memory_scene_focus_content_area", icon, open);
        editRemoteView(extras, "miui.focus.rvNight", resources,
                "memory_scene_focus_icon", "memory_scene_focus_content_area", icon, open);
        editRemoteView(extras, "miui.focus.rv.island.expand", resources,
                "memory_scene_focus_icon", "memory_scene_focus_content_area", icon, open);
        editRemoteView(extras, "miui.focus.rv.tiny", resources,
                "memory_scene_tiny_icon", "memory_scene_tiny_content_area", icon, open);
        editRemoteView(extras, "miui.focus.rv.tinyNight", resources,
                "memory_scene_tiny_icon", "memory_scene_tiny_content_area", icon, open);
        editRemoteView(extras, "miui.rear.rv", resources,
                "memory_scene_rear_icon", "memory_scene_rear_content_area", icon, open);
        editRemoteView(extras, "miui.rear.rvAOD", resources,
                "memory_scene_rear_icon", "memory_scene_rear_content_area", icon, open);
    }

    private static volatile Context voiceContext;

    private static void editRemoteView(Bundle extras, String key, Resources resources,
                                       String iconName, String contentName, Icon icon,
                                       PendingIntent open) {
        try {
            RemoteViews views = extras.getParcelable(key, RemoteViews.class);
            if (views == null) return;
            String packageName = Constants.PKG_VOICE_ASSIST;
            int contentId = resources.getIdentifier(contentName, "id", packageName);
            if (contentId != 0) views.setOnClickPendingIntent(contentId, open);
            if (icon != null) {
                int iconId = resources.getIdentifier(iconName, "id", packageName);
                if (iconId != 0) views.setImageViewIcon(iconId, icon);
            }
        } catch (Throwable error) {
            log("RemoteViews " + key + " skipped: " + error.getClass().getSimpleName());
        }
    }

    private static Icon animatedIcon(String model) {
        Context context = voiceContext;
        String safe = safeModel(model);
        if (context == null || safe.isEmpty()) return null;
        Uri uri = Constants.PROVIDER_URI.buildUpon().appendPath("asset").appendPath(safe).build();
        try (AssetFileDescriptor descriptor =
                     context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (descriptor == null) return null;
            return Icon.createWithContentUri(uri);
        } catch (Throwable error) {
            log("animated model unavailable, keeping Xiaomi fallback: "
                    + error.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean looksLikeMemoryScene(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(raw);
            return "memory".equals(root.optString("business"))
                    && root.optJSONObject("param_island") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String sceneFor(PickupEvent event) {
        String category = event.category.toLowerCase(Locale.ROOT);
        if (category.contains("deliver") || category.contains("express")
                || category.contains("package") || category.contains("parcel")) {
            return "delivery";
        }
        if (category.contains("catering") || category.contains("food")
                || category.contains("takeout") || category.contains("burger")) {
            return "pickup_food";
        }
        return "pickup_drink";
    }

    private static String pictureKey(String scene) {
        return switch (scene) {
            case "pickup_food" -> "food";
            case "delivery" -> "pickup_code";
            default -> "drink";
        };
    }

    private static boolean validEvent(PickupEvent event, String code) {
        return event != null && event.isFresh(Constants.EVENT_MAX_AGE_MS)
                && Constants.isSourcePackage(event.sourcePackage)
                && !code.isEmpty();
    }

    private static boolean trustedPendingIntent(PendingIntent pendingIntent, PickupEvent event) {
        if (pendingIntent == null || event == null) return false;
        try {
            String creator = pendingIntent.getCreatorPackage();
            return Constants.PKG_AICR.equals(creator) || event.sourcePackage.equals(creator);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String safeModel(String value) {
        String model = PickupEvent.clean(value).replace('\\', '/');
        return model.matches("base_bg_[a-z0-9_]{1,84}\\.webp") ? model : "";
    }

    private static String cleanLine(String value, int max) {
        return PickupEvent.truncate(PickupEvent.clean(value).replaceAll("[\\r\\n]+", " "), max);
    }

    private static void enableFloat(Notification notification) {
        try {
            Object extra = XposedHelpers.getObjectField(notification, "extraNotification");
            if (extra != null) XposedHelpers.callMethod(extra, "setEnableFloat", true);
        } catch (Throwable ignored) {
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }

    private static void log(String message) {
        XposedBridge.log("AutoPickupIsland/VoiceAssist: " + message);
    }

    private static final class Decoration {
        final PickupEvent event;
        final String code;
        final String semanticCode;
        final String instance = java.util.UUID.randomUUID().toString();
        final String product;
        final int orderState;
        final AtomicBoolean cloudStarted = new AtomicBoolean();
        final String detail;
        final String model;
        final String scene;
        final PendingIntent open;
        final NativePublishReceipt receipt;
        final BroadcastReceiver.PendingResult pendingResult;
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicBoolean released = new AtomicBoolean();
        volatile boolean leased;

        Decoration(PickupEvent event, String code, String product, String detail,
                   String model, String scene,
                   PendingIntent open, long deadline, BroadcastReceiver.PendingResult pendingResult, int orderState) {
            this.event = event;
            this.code = code;
            this.semanticCode = NativePickupOwnership.semantic(code, NativePickupOwnership.WAITING_LABEL.equals(code));
            this.product = product;
            this.orderState = orderState;
            this.detail = detail;
            this.model = model;
            this.scene = scene;
            this.open = open;
            this.receipt = new NativePublishReceipt(deadline);
            this.pendingResult = pendingResult;
        }

        void finish(int code) {
            if (!finished.compareAndSet(false, true)) return;
            try { pendingResult.setResultCode(code); }
            catch (Throwable ignored) { }
            finally {
                try { pendingResult.finish(); } catch (Throwable ignored) { }
            }
        }

        void release() {
            if (leased && released.compareAndSet(false, true)) OUTSTANDING.decrementAndGet();
        }
    }
}
