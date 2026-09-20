package io.github.mio.autopickupisland;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.BroadcastOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import java.util.HashSet;
import java.util.Set;

/** Optional event-only signal source. Never accesses event text/source or accessibility nodes. */
public final class PageChangeSignalService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Set<String> packages = Set.of();
    private String pendingPackage = "";
    private boolean queued;
    private long lastSent;
    private final Runnable emit = this::emit;
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { reload(); }
    };

    @Override protected void onServiceConnected() {
        reload();
        try { getContentResolver().registerContentObserver(Constants.PROVIDER_URI, true, observer); }
        catch (RuntimeException ignored) { }
    }

    private void reload() {
        cancel();
        try {
            Set<String> updated = new HashSet<>();
            for (Rule rule : RuleRepository.get(this).allRules()) {
                if (!rule.packageName.isEmpty() && !Constants.MODULE_PACKAGE.equals(rule.packageName)) {
                    updated.add(rule.packageName);
                }
            }
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) { packages = Set.of(); return; }
            // Empty packageNames means ALL applications on Android: never pass an empty array.
            info.packageNames = updated.isEmpty() ? new String[]{Constants.MODULE_PACKAGE}
                    : updated.toArray(new String[0]);
            setServiceInfo(info);
            packages = Set.copyOf(updated);
        } catch (Throwable ignored) { packages = Set.of(); }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
            CharSequence source = event.getPackageName();
            String pkg = source == null ? "" : source.toString();
            if (!packages.contains(pkg)) return;
            int changes = event.getContentChangeTypes();
            if (!relevantChange(changes)) return;
            PowerManager power = getSystemService(PowerManager.class);
            if (power == null || !power.isInteractive()) { cancel(); return; }
            pendingPackage = pkg;
            // One trailing signal, at most once/second, no repeating timer or starvation on scroll.
            if (!queued) {
                queued = true;
                handler.postDelayed(emit, signalDelay(SystemClock.elapsedRealtime(), lastSent));
            }
        } catch (Throwable ignored) { cancel(); }
    }

    static boolean relevantChange(int changes) {
        return changes == 0 || (changes & (AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
                | AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)) != 0;
    }
    static long signalDelay(long now, long previous) { return Math.max(300L, 1_000L - (now - previous)); }

    private void emit() {
        queued = false;
        String pkg = pendingPackage;
        pendingPackage = "";
        try {
            if (!packages.contains(pkg)) return;
            PowerManager power = getSystemService(PowerManager.class);
            if (power == null || !power.isInteractive()) return;
            Intent intent = new Intent(Constants.ACTION_CAPTURE_REQUEST)
                    .setComponent(new ComponentName(Constants.PKG_PRIVILEGED_ASSIST,
                            Constants.PRIVILEGED_ASSIST_RECEIVER))
                    .putExtra(Constants.EXTRA_SOURCE_PACKAGE, pkg)
                    .putExtra(Constants.EXTRA_OBSERVED_AT, System.currentTimeMillis())
                    .putExtra(Constants.EXTRA_CONTENT_CHANGED, true);
            sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
            lastSent = SystemClock.elapsedRealtime();
        } catch (Throwable ignored) { }
    }

    private void cancel() { handler.removeCallbacks(emit); queued = false; pendingPackage = ""; }
    @Override public void onInterrupt() { cancel(); }
    @Override public void onDestroy() {
        cancel();
        try { getContentResolver().unregisterContentObserver(observer); } catch (RuntimeException ignored) { }
        super.onDestroy();
    }
}
