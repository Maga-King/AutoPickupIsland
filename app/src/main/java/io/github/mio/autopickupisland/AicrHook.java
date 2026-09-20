package io.github.mio.autopickupisland;

import android.app.Application;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

final class AicrHook {
    private static final AtomicBoolean WORKER_RUNNING = new AtomicBoolean();
    private static final Object STATE_LOCK = new Object();
    private static volatile Work pending;
    private static volatile long epoch;
    private static volatile String lastPublished = "";
    private static volatile long lastPublishedAt;

    private AicrHook() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.processName.endsWith(":cognitionService")) return;
        AicrTopActivityRelay.install(lpparam.classLoader);
        hookManifestIngress(lpparam.classLoader);
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Context base = (Context) param.args[0];
                            if (base == null) return;
                            Context application = base.getApplicationContext();
                            Context context = application == null ? base : application;
                            AicrTopActivityRelay.onContextReady(context);
                            BroadcastReceiver receiver = new BroadcastReceiver() {
                                @Override
                                public void onReceive(Context receiverContext, Intent intent) {
                                    try {
                                        if (Constants.ACTION_DISMISS.equals(intent.getAction())) {
                                            if (trustedDismissSender(this, receiverContext)) {
                                                dismiss(receiverContext, intent);
                                            }
                                            return;
                                        }
                                        boolean moduleEvent = Constants.ACTION_EVENT.equals(intent.getAction());
                                        boolean systemEvent = Constants.ACTION_SYSTEM_EVENT.equals(intent.getAction());
                                        if (!moduleEvent && !systemEvent) return;
                                        if (systemEvent) {
                                            if (!BroadcastVerifier.isSystem(this)) return;
                                        } else {
                                            if (!BroadcastVerifier.isPackage(this, receiverContext,
                                                    Constants.MODULE_PACKAGE)) return;
                                        }
                                        PickupEvent event = PickupEvent.fromBundle(intent.getExtras());
                                        accept(context, event, systemEvent);
                                    } catch (Throwable error) {
                                        log("dynamic ingress failed: " + error);
                                    }
                                }
                            };
                            IntentFilter filter = new IntentFilter(Constants.ACTION_EVENT);
                            filter.addAction(Constants.ACTION_SYSTEM_EVENT);
                            filter.addAction(Constants.ACTION_DISMISS);
                            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                            BridgeClient.heartbeat(context);
                            log("bridge ready in " + lpparam.processName);
                            HookHealth.state("ingress", "识别广播接收器已注册");
                        } catch (Throwable error) {
                            log("attach failed: " + error);
                        }
                    }
                });
    }

    private static void hookManifestIngress(ClassLoader loader) {
        Class<?> receiverClass = XposedHelpers.findClassIfExists(
                Constants.AICR_WAKE_RECEIVER, loader);
        if (receiverClass == null) return;
        XposedBridge.hookAllMethods(receiverClass, "onReceive", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.thisObject instanceof BroadcastReceiver receiver)
                            || param.args.length < 2
                            || !(param.args[0] instanceof Context context)
                            || !(param.args[1] instanceof Intent intent)) return;
                    if (NativeWechatNavigation.handle(context, intent, loader)) {
                        param.setResult(null);
                        return;
                    }
                    if (Constants.ACTION_CLEAR_STATE.equals(intent.getAction())) {
                        param.setResult(null);
                        if (BroadcastVerifier.isPackage(receiver, context, Constants.MODULE_PACKAGE)) {
                            synchronized (STATE_LOCK) {
                                epoch++;
                                pending = null;
                                lastPublished = "";
                                lastPublishedAt = 0L;
                                RouteCache.clear();
                            }
                            log("recognition queue and in-memory dedupe cleared");
                        }
                        return;
                    }
                    if (Constants.ACTION_CONFIG_CHANGED.equals(intent.getAction())) {
                        param.setResult(null);
                        if (BroadcastVerifier.isPackage(receiver, context, Constants.MODULE_PACKAGE)) {
                            RuleRepository.invalidate();
                            log("configuration changed: rule cache invalidated");
                        }
                        return;
                    }
                    if (Constants.ACTION_DISMISS.equals(intent.getAction())) {
                        if (trustedDismissSender(receiver, context)) dismiss(context, intent);
                        param.setResult(null);
                        return;
                    }
                    boolean moduleEvent = Constants.ACTION_EVENT.equals(intent.getAction());
                    boolean systemEvent = Constants.ACTION_SYSTEM_EVENT.equals(intent.getAction());
                    if (!moduleEvent && !systemEvent) return;
                    if (systemEvent) {
                        if (!BroadcastVerifier.isSystem(receiver)) return;
                    } else {
                        String sender = BroadcastVerifier.sender(receiver, context);
                        if (!Constants.MODULE_PACKAGE.equals(sender)) return;
                    }
                    PickupEvent event = PickupEvent.fromBundle(intent.getExtras());
                    Context application = context.getApplicationContext();
                    accept(application == null ? context : application, event, systemEvent);
                } catch (Throwable error) {
                    log("manifest ingress failed: " + error);
                }
            }
        });
    }

    private static void accept(Context context, PickupEvent event, boolean systemEvent) {
        if (event == null) return;
        HookHealth.callback("识别事件已到达");
        RouteCache.update(event);
        if (systemEvent) BridgeClient.storeSystemEvent(context, event);
        if (!event.navigationOnly) enqueue(context, event);
    }

    private static void enqueue(Context context, PickupEvent event) {
        if (event == null || event.navigationOnly || !event.isFresh(Constants.EVENT_MAX_AGE_MS)) return;
        synchronized (STATE_LOCK) { pending = new Work(event, epoch); }
        if (!WORKER_RUNNING.compareAndSet(false, true)) return;
        new Thread(() -> drain(context.getApplicationContext()), "MioColorOsPCR").start();
    }

    private static void drain(Context context) {
        try {
            while (true) {
                Work work;
                synchronized (STATE_LOCK) { work = pending; pending = null; }
                if (work == null) break;
                process(context, work.event, work.epoch);
            }
        } finally {
            WORKER_RUNNING.set(false);
            if (pending != null && WORKER_RUNNING.compareAndSet(false, true)) {
                new Thread(() -> drain(context), "MioColorOsPCR").start();
            }
        }
    }

    private record Work(PickupEvent event, long epoch) { }

    private static void process(Context context, PickupEvent event, long workEpoch) {
        try {
            if (workEpoch != epoch) return;
            RuleRepository repository = RuleRepository.get(context);
            Rule rule = repository.recognitionRule(context, event);
            if (rule == null) {
                BridgeClient.acknowledge(context, event, "当前规则未匹配，未使用旧事件策略", "", "ColorOS-policy");
                return;
            }
            ColorOsRecognitionPolicy policy = repository.recognitionPolicy();
            event.needWaitingStatus = rule.needWaitingStatus;
            event.useCloud = rule.useCloud(event.recognitionPath());
            boolean model = BridgeClient.modelEnabled(context);
            ColorOsRecognizer.Result result = new ColorOsRecognizer().recognize(context, event, model);
            if (workEpoch != epoch) return;
            result.orderState = policy.orderState(event, rule, result, System.currentTimeMillis());
            if (result.found() && policy.rejectRealCodePage(event.isMiniProgram(), rule,
                    event.recognitionPath(), event.fuzzyMatch, result.orderPage)) {
                BridgeClient.acknowledge(context, event, "ColorOS 原版页面策略不允许出卡", "", result.engine);
                return;
            }
            boolean waiting = result.waitingForCode(event);
            if (waiting) {
                BridgeClient.acknowledge(context, event, "原版等待出码；按设置隐藏无码占位", "", result.engine);
                return;
            }
            if (!result.found() && !waiting) {
                BridgeClient.acknowledge(context, event,
                        "ActualMiniProgramPathUnavailable".equals(result.failure)
                                ? "缺少真实小程序路径，未使用配置路径代填"
                                : result.failure.isEmpty() ? "原版解析无取餐码结果" : "ColorOS 原版解析器运行失败", "",
                        result.failure.isEmpty() ? result.engine : result.failure);
                return;
            }
            if (!ColorOsRecognitionPolicy.acceptNewLocalCandidate(result.orderState)) {
                BridgeClient.acknowledge(context, event,
                        result.orderState == 2 ? "原版判定订单已完成，不新发取餐卡" : "原版详情页准备状态未满足，不新发取餐卡",
                        "", result.engine);
                // o6.r.a0 close/update still needs the owned native instance and lifecycle.
                // Do not approximate it by cancelling all pickup cards or manual XiaoAi cards.
                return;
            }
            String key = event.sourcePackage + '|' + event.appId + '|' + result.code;
            long now = System.currentTimeMillis();
            // Native ownership checks actual showing/closed records. A stale 10-minute
            // publication cache must not prevent a cleared or dismissed native card returning.
            VoiceAssistNativeIsland.Delivery delivery = VoiceAssistNativeIsland.request(context, event, result);
            if (delivery == VoiceAssistNativeIsland.Delivery.SUPPRESSED) {
                BridgeClient.acknowledge(context, event, "保留已显示卡片或遵循当日确认记录", result.code, result.engine);
                return;
            }
            if (delivery == VoiceAssistNativeIsland.Delivery.UNKNOWN) {
                BridgeClient.acknowledge(context, event, "原生通知投递未确认，未重复发送兼容卡", result.code, result.engine);
                return;
            }
            boolean nativeCard = delivery == VoiceAssistNativeIsland.Delivery.POSTED;
            if (waiting && !nativeCard) {
                BridgeClient.acknowledge(context, event, "原版等待出码；原生占位投递未成功", "", result.engine);
                return; // No fake code or incompatible fallback template.
            }
            if (!nativeCard) {
                if (PickupDeduplication.suppress(key, lastPublished, now, lastPublishedAt)) {
                    BridgeClient.acknowledge(context, event, "兼容卡临时去重已抑制", result.code, result.engine);
                    return;
                }
                IslandPublisher.publish(context, event, result);
            }
            synchronized (STATE_LOCK) {
                if (workEpoch != epoch) return;
                if (!waiting) { lastPublished = key; lastPublishedAt = now; }
            }
            BridgeClient.acknowledge(context, event,
                    waiting ? "已提交等待出码占位卡" : nativeCard ? "已提交超级小爱原生取餐通知" : "已通过兼容模板自动上岛",
                    result.code, result.engine
                            + (result.product.isEmpty() ? "" : " / " + result.product));
        } catch (Throwable error) {
            log("process failed: " + error);
            if (workEpoch == epoch) BridgeClient.acknowledge(context, event, "处理失败", "", error.toString());
        } finally {
            if (workEpoch == epoch) BridgeClient.heartbeat(context);
        }
    }

    private static boolean trustedDismissSender(BroadcastReceiver receiver, Context context) {
        return BroadcastVerifier.isPackage(receiver, context, Constants.PKG_AICR)
                || BroadcastVerifier.isPackage(receiver, context, Constants.PKG_SYSTEM_UI)
                || BroadcastVerifier.isSystem(receiver);
    }

    private static void dismiss(Context context, Intent intent) {
        try {
            int id = intent.getIntExtra(Constants.EXTRA_NOTIFICATION_ID, -1);
            String tag = PickupEvent.clean(
                    intent.getStringExtra(Constants.EXTRA_NOTIFICATION_TAG));
            if (id < 0 || !tag.startsWith("mio.coloros.pickup.")) return;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(tag, id);
        } catch (Throwable error) {
            log("dismiss failed safely: " + error);
        }
    }

    private static void log(String message) {
        XposedBridge.log("AutoPickupIsland/AICR: " + message);
    }
}
