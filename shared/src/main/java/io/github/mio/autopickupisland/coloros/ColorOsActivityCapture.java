package io.github.mio.autopickupisland.coloros;

import android.app.Activity;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Activity-token root matching used by ColorOS, adapted to OS4's public window
 * snapshot API. getRootViews is blocked for targetSdk > 30 on OS4; its token and
 * subwindow filtering was ported from the device's framework.smali instead.
 * No decor-only or unfiltered all-window capture is used.
 * This class installs no process bootstrap or hidden-API exemptions.
 */
public final class ColorOsActivityCapture {
    private ColorOsActivityCapture() { }
    public static String capabilities() {
        try { Access access = new Access(); return access.visible == null ? "NO_VISIBILITY_API" : "PUBLIC_SNAPSHOT_NATIVE_VISIBILITY"; }
        catch (Throwable error) { return "UNSUPPORTED:" + error.getClass().getSimpleName(); }
    }
    public static void request(Activity activity, ColorOsWebViewClient client,
                               ColorOsWebViewSelector.Options selection, ColorOsWebViewClient.Request options,
                               BooleanSupplier current, Consumer<ColorOsWebViewClient.Result> callback) {
        requestTracked(activity, client, selection, options, current, callback);
    }
    public static ColorOsWebViewClient.Ticket requestTracked(Activity activity, ColorOsWebViewClient client,
                               ColorOsWebViewSelector.Options selection, ColorOsWebViewClient.Request options,
                               BooleanSupplier current, Consumer<ColorOsWebViewClient.Result> callback) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper() || activity == null || activity.isDestroyed()
                    || current == null || !current.getAsBoolean()) {
                respond(callback, ColorOsWebViewClient.Status.STALE); return null;
            }
            // The isolated fixture uses FLAG_SECURE solely to suppress the
            // installed auto module while exercising its OWN synthetic pages.
            // Never make that test exception apply to user applications.
            if (activity.getWindow() == null || ((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0
                    && !"io.github.mio.autopickupfixture".equals(activity.getPackageName()))) {
                respond(callback, ColorOsWebViewClient.Status.UNSUPPORTED); return null;
            }
            Access access;
            try { access = new Access(); }
            catch (Throwable ignored) { respond(callback, ColorOsWebViewClient.Status.UNSUPPORTED); return null; }
            Object token = access.token.invoke(activity);
            if (!(token instanceof IBinder)) { respond(callback, ColorOsWebViewClient.Status.UNSUPPORTED); return null; }
            List<View> snapshot = WindowInspector.getGlobalWindowViews(); // Only THIS process's mViews.
            List<Entry> entries = entries(snapshot);
            List<View> ordered = filter(entries, (IBinder) token);
            // OEM does its lookup under mLock. Public API exposes a snapshot, not
            // that lock: reject if window membership/metadata changed during our
            // copy instead of mixing two different window states. No retry/poll.
            if (!entries.equals(entries(WindowInspector.getGlobalWindowViews()))) {
                respond(callback, ColorOsWebViewClient.Status.STALE); return null;
            }
            var selected = client.selectViews(ordered, selection, access.visible);
            if (selected.status() != ColorOsWebViewSelector.Status.OK) {
                respond(callback, selected.status() == ColorOsWebViewSelector.Status.LIMIT
                        ? ColorOsWebViewClient.Status.LIMIT : ColorOsWebViewClient.Status.UNSUPPORTED); return null;
            }
            return client.requestBatch(selected.views(), selection.evaluateMethod(), options, current, callback);
        } catch (Throwable ignored) { respond(callback, ColorOsWebViewClient.Status.FAILED); }
        return null;
    }
    private static void respond(Consumer<ColorOsWebViewClient.Result> callback, ColorOsWebViewClient.Status status) {
        if (callback != null) try { callback.accept(new ColorOsWebViewClient.Result(status, "", "", 0)); }
        catch (Throwable ignored) { }
    }
    private static final class Access {
        final Method token = Activity.class.getMethod("getActivityToken");
        final ColorOsWebViewSelector.Visibility visible = ColorOsWebViewSelector.frameworkVisibility();
        Access() throws ReflectiveOperationException { }
    }
    private record Entry(View view, IBinder owner, IBinder window, int type) { }
    private static List<Entry> entries(List<View> snapshot) {
        if (snapshot == null || snapshot.size() > 64) throw new IllegalArgumentException("WindowLimit");
        List<Entry> entries = new ArrayList<>();
        for (View view : snapshot) {
            if (view == null || !(view.getLayoutParams() instanceof WindowManager.LayoutParams params))
                throw new IllegalArgumentException("WindowMetadata");
            entries.add(new Entry(view, params.token, view.getWindowToken(), params.type));
        }
        return entries;
    }
    /** Exposed for synthetic token-boundary regression, not a global capture API. */
    public static List<View> filterSnapshot(List<View> snapshot, IBinder token) {
        return filter(entries(snapshot), token);
    }
    private static List<View> filter(List<Entry> entries, IBinder token) {
        if (token == null) return List.of();
        List<View> selected = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.owner == null) continue;
            boolean match = entry.owner == token;
            if (!match && entry.type >= 1000 && entry.type <= 1999) {
                for (Entry parent : entries) {
                    if (entry.owner == parent.window && parent.owner == token) { match = true; break; }
                }
            }
            if (match) selected.add(entry.view);
        }
        return List.copyOf(selected);
    }
}
