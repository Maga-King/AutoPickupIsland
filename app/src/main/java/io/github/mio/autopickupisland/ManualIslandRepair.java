package io.github.mio.autopickupisland;

import android.app.Application;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import java.util.Locale;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

final class ManualIslandRepair {
    private static volatile boolean hooked;

    private ManualIslandRepair() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (hooked) return;
                            Context base = (Context) param.args[0];
                            if (base == null) return;
                            Context application = base.getApplicationContext();
                            Context context = application == null ? base : application;
                            hooked = true;
                            try {
                                registerRouteBridge(context);
                                requestCurrentRoute(context);
                            } catch (Throwable error) {
                                XposedBridge.log("AutoPickupIsland/Repair route bridge failed: " + error);
                            }
                            XC_MethodHook repair = new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam hookParam) {
                                    try {
                                        for (Object argument : hookParam.args) {
                                            if (argument instanceof Notification notification) {
                                                repair(context, notification);
                                                break;
                                            }
                                        }
                                    } catch (Throwable error) {
                                        XposedBridge.log("AutoPickupIsland/Repair callback failed: " + error);
                                    }
                                }
                            };
                            XposedBridge.hookAllMethods(NotificationManager.class, "notify", repair);
                            XposedBridge.hookAllMethods(NotificationManager.class, "notifyAsUser", repair);
                        } catch (Throwable error) {
                            XposedBridge.log("AutoPickupIsland/Repair attach failed: " + error);
                        }
                    }
                });
    }

    private static void repair(Context context, Notification notification) {
        try {
            Bundle extras = notification.extras;
            if (extras == null || extras.getBoolean("mio.auto_pickup", false)) return;
            String focus = extras.getString("miui.focus.param.custom", "");
            if (focus.isEmpty()) focus = extras.getString("miui.focus.param", "");
            if (focus.isEmpty() || !looksLikePickup(extras, focus)) return;
            PickupEvent event = RouteCache.best();
            event = newer(event, BridgeClient.peek(context, false));
            event = newer(event, BridgeClient.peek(context, true));
            if (event == null || !event.isFresh(Constants.NAV_MAX_AGE_MS)) return;

            PendingIntent open = IslandPublisher.createOpenPendingIntent(context, event);
            VoiceAssistNativeIsland.bindContentClicks(context, notification, open);
            extras.putBoolean("mio.manual_pickup_click_repaired", true);
            extras.putString("mio.pickup.appId", event.appId);
            extras.putString("mio.pickup.path", event.openPath());
            XposedBridge.log("AutoPickupIsland/Repair: rebound pickup content only to "
                    + event.appId + '/' + event.openPath());
        } catch (Throwable error) {
            XposedBridge.log("AutoPickupIsland/Repair failed: " + error);
        }
    }

    private static void registerRouteBridge(Context context) {
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent intent) {
                if (!Constants.ACTION_ROUTE.equals(intent.getAction())) return;
                PickupEvent event = PickupEvent.fromBundle(intent.getExtras());
                String sender = BroadcastVerifier.sender(this, receiverContext);
                if (event == null || !Constants.isSourcePackage(sender)
                        || !sender.equals(event.sourcePackage)) return;
                RouteCache.update(event);
            }
        }, new IntentFilter(Constants.ACTION_ROUTE), Context.RECEIVER_EXPORTED);
    }

    @SuppressWarnings("deprecation")
    private static void requestCurrentRoute(Context context) {
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return;
            ComponentName top = tasks.get(0).topActivity;
            if (top == null || !Constants.isSourcePackage(top.getPackageName())) return;
            context.sendBroadcast(new Intent(Constants.ACTION_ROUTE_REQUEST)
                    .setPackage(top.getPackageName()));
        } catch (Throwable ignored) {
        }
    }

    private static PickupEvent newer(PickupEvent first, PickupEvent candidate) {
        if (candidate == null || !candidate.isFresh(Constants.NAV_MAX_AGE_MS)) return first;
        if (first == null || candidate.timestamp > first.timestamp) return candidate;
        return first;
    }

    private static boolean looksLikePickup(Bundle extras, String focus) {
        StringBuilder text = new StringBuilder(focus);
        for (String key : new String[]{"android.title", "android.text", "android.bigText",
                "android.subText", "android.infoText"}) {
            CharSequence value = extras.getCharSequence(key);
            if (value != null) text.append('\n').append(value);
        }
        String value = text.toString().toLowerCase(Locale.ROOT);
        return value.contains("取餐") || value.contains("取货码") || value.contains("取茶")
                || value.contains("餐号") || value.contains("pickup")
                || value.contains("meal_order") || value.contains("mealorder");
    }

}
