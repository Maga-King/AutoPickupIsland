package io.github.mio.autopickupisland;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import java.time.ZoneId;
import java.util.*;
import static io.github.mio.autopickupisland.ColorOsLifecycleRules.*;

/** Xiaomi host identity/receipt adapter. Only v1-marked automatic cards are owned.
 * Active notification extras survive the voice process; no observer, alarm or OCR.
 * This is not the complete ColorOS ledger (cloud/timeout sources remain separate).
 */
final class NativePickupOwnership {
    static final String WAITING_LABEL = "棍母";
    static final String IDENTITY = "mio.pickup.identity.v1", INSTANCE = "mio.pickup.instance.v1";
    static final String CODE = "mio.pickup.semanticCode.v1";
    private static final String PREFS = "mio_pickup_closed_v1";
    record Owned(int id, String identity, String instance, String code, long postTime) { }

    static boolean replaces(Owned posted, Owned previous) {
        // System notification timestamps, not wall time guessed by delayed callbacks.
        // Equal timestamps are ambiguous: keep both rather than retire a newer instance.
        return posted != null && previous != null && posted.id != previous.id
                && posted.identity.equals(previous.identity)
                && !posted.instance.equals(previous.instance)
                && previous.postTime > 0 && previous.postTime < posted.postTime;
    }

    static String identity(PickupEvent event) {
        Identity identity = ColorOsLifecycleRules.identity(event.webView, event.actualTaskLabel,
                event.sourcePackage, event.sourcePackage, event.userId);
        return identity.userId() + ":" + identity.identifier();
    }
    static String semantic(String code, boolean waiting) { return waiting ? UNKNOWN_CODE : code; }
    static void mark(Notification notification, PickupEvent event, String code, String instance) {
        notification.extras.putString(IDENTITY, identity(event));
        notification.extras.putString(INSTANCE, instance);
        notification.extras.putString(CODE, code);
    }
    static List<Owned> active(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) throw new IllegalStateException("NotificationServiceUnavailable");
        List<Owned> result = new ArrayList<>();
        for (StatusBarNotification entry : manager.getActiveNotifications()) {
            if (entry == null || entry.getTag() != null || !Constants.PKG_VOICE_ASSIST.equals(entry.getPackageName())) continue;
            Bundle b = entry.getNotification().extras;
            if (b == null || !b.getBoolean("mio.auto_pickup") || !b.getBoolean("mio.voiceassist.native_memory_scene")) continue;
            String identity = b.getString(IDENTITY, ""), instance = b.getString(INSTANCE, ""), code = b.getString(CODE, "");
            if (!identity.isEmpty() && instance.matches("[a-f0-9-]{36}") && !code.isEmpty())
                result.add(new Owned(entry.getId(), identity, instance, code, entry.getPostTime()));
        }
        return result;
    }
    static boolean suppress(Context context, PickupEvent event, String code) {
        String identity = identity(event);
        long closedAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(historyKey(identity, code), Long.MIN_VALUE);
        if (recordableCode(code) && closedAt != Long.MIN_VALUE && closedToday(
                new Order(code, 1, Source.LOCAL, event.sequence, event.eventSource),
                new Closed(code, 4, closedAt, Long.MAX_VALUE), System.currentTimeMillis(), ZoneId.systemDefault())) {
            HookHealth.state("dedup", "当日已取餐记录拦截；可在模块中解除"); return true;
        }
        for (Owned showing : active(context)) {
            if (identity.equals(showing.identity) && (code.equals(showing.code)
                    || preserveShowingForUnknown(true, new Order(code, 1, Source.LOCAL, event.sequence, event.eventSource)))) {
                HookHealth.state("dedup", "现有同身份卡片拦截重复发布"); return true;
            }
        }
        return false;
    }
    static Owned byId(Context context, int id) {
        for (Owned owned : active(context)) if (owned.id == id) return owned;
        return null;
    }
    static synchronized void confirmed(Context context, Owned before) {
        if (before == null || !recordableCode(before.code)) return;
        // The original receiver must have actually removed this exact instance.
        Owned after = byId(context, before.id);
        if (after != null && before.instance.equals(after.instance)) return;
        var prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        var edit = prefs.edit();
        long now = System.currentTimeMillis();
        var entries = prefs.getAll();
        int retained = 0;
        String key = historyKey(before.identity, before.code);
        boolean retainedKey = false;
        // Bounded local persistence. Expired days have no effect on original closedToday.
        for (var item : entries.entrySet()) {
            if (item.getValue() instanceof Long time && !java.time.Instant.ofEpochMilli(time)
                    .atZone(ZoneId.systemDefault()).toLocalDate().equals(java.time.Instant.ofEpochMilli(now)
                            .atZone(ZoneId.systemDefault()).toLocalDate())) edit.remove(item.getKey());
            else {
                retained++;
                if (key.equals(item.getKey())) retainedKey = true;
            }
        }
        if (retained < 512 || retainedKey) edit.putLong(key, now);
        edit.apply();
    }

    static synchronized int clearConfirmed(Context context) {
        var prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int count = prefs.getAll().size();
        if (!prefs.edit().clear().commit()) throw new IllegalStateException("ClosedHistoryWriteFailed");
        HookHealth.state("dedup", "已清除全部已取餐记录：" + count + " 条");
        return count;
    }
    private static String historyKey(String identity, String code) {
        return android.util.Base64.encodeToString((identity + '\n' + code).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
    }
    private NativePickupOwnership() { }
}
