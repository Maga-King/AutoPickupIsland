package io.github.mio.autopickupfixture;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import io.github.mio.autopickupisland.coloros.ColorOsViewMetadata;
import io.github.mio.autopickupisland.coloros.ColorOsNativeSnapshot;
import io.github.mio.autopickupisland.ColorOsNativeExtractor;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Timer;
import java.util.TimerTask;

/** Explicit own-process attached-View checks. No cloud, real app capture,
 * notifications, confirmed-ledger writes or global hidden-API exemptions. */
public final class NativeMetadataInstrumentation extends Instrumentation {
    private int assertions;
    private NativeMetadataAuditActivity activity;
    private Dialog dialog;
    private Throwable failure;
    private String capabilities = "NOT_RUN";
    private String nativeText = "";
    private final AtomicBoolean finished = new AtomicBoolean();
    private Timer deadline;
    private boolean nativeChain;
    private boolean windowInteraction;
    private volatile String phase = "create";
    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        assertions++;
    }
    private void main(Runnable action) {
        runOnMainSync(() -> {
            if (failure != null) return;
            try { action.run(); } catch (Throwable error) { failure = error; }
        });
        if (failure != null) throw new IllegalStateException("ViewAudit", failure);
    }
    private ColorOsViewMetadata.Result read(View view) {
        var result = ColorOsViewMetadata.read(view);
        check("OK".equals(result.status()), "metadata missing: " + result.status());
        return result;
    }
    private void visible(boolean expected, String label) {
        var result = read(activity.code);
        check(result.extras().getBoolean("isVisibleToUserIgnoreRoot") == expected, label);
    }
    @Override public void onCreate(Bundle args) {
        if (args == null || !"native-metadata".equals(args.getString("operation"))) {
            Bundle result = new Bundle(); result.putString("failure", "Explicit operation required");
            finish(Activity.RESULT_CANCELED, result); return;
        }
        nativeChain = "true".equals(args.getString("native_chain"));
        windowInteraction = "true".equals(args.getString("window_interaction"));
        // startActivitySync / waitForIdleSync can wait indefinitely if the user
        // changes foreground. Diagnostic-only one-shot deadline, not polling.
        deadline = new Timer("MioNativeAuditDeadline", true);
        deadline.schedule(new TimerTask() {
            @Override public void run() {
                Bundle timeout = new Bundle();
                timeout.putBoolean("completed", false);
                timeout.putString("failure", "FixtureForegroundOrIdleTimeout");
                timeout.putString("phase", phase);
                finishOnce(Activity.RESULT_CANCELED, timeout);
            }
        }, 15_000L);
        start();
    }
    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            phase = "launch";
            Intent intent = new Intent(getTargetContext(), NativeMetadataAuditActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (NativeMetadataAuditActivity) startActivitySync(intent);
            phase = "idle";
            waitForIdleSync();
            phase = "draw";
            CountDownLatch drawn = new CountDownLatch(1);
            main(() -> activity.code.postOnAnimation(drawn::countDown));
            if (!drawn.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("DrawTimeout");
            check("WRONG_THREAD".equals(ColorOsViewMetadata.read(activity.code).status()), "off-main refused");
            check("NO_VIEW".equals(ColorOsViewMetadata.read(null).status()), "null refused");
            main(() -> {
                check(activity.code.isAttachedToWindow() && activity.code.getWidth() > 0, "real attached/layout");
                var initial = ColorOsViewMetadata.read(activity.code);
                capabilities = initial.status() + ":visibility=" + initial.visibilityAvailable()
                        + ":bounds=" + initial.boundsAvailable();
                check("OK".equals(initial.status()), "OS4 capabilities: " + capabilities);
                check(TextView.class.getName().equals(initial.extras().getString("extraClassName")), "actual class");
                check(!initial.extras().containsKey("text"), "metadata does not collect text");
                visible(true, "visible text");
                activity.code.setAlpha(0); visible(false, "zero alpha"); activity.code.setAlpha(1);
                activity.parent.setAlpha(0); visible(false, "transparent ancestor"); activity.parent.setAlpha(1);
                activity.code.setVisibility(View.INVISIBLE); visible(false, "invisible text"); activity.code.setVisibility(View.VISIBLE);
                activity.parent.setVisibility(View.INVISIBLE); visible(false, "invisible ancestor"); activity.parent.setVisibility(View.VISIBLE);
                activity.code.setTranslationX(100000); visible(false, "offscreen/clipped"); activity.code.setTranslationX(0);
                visible(true, "restored text");
                try {
                    Method transition = View.class.getMethod("setTransitionAlpha", float.class);
                    transition.invoke(activity.parent, 0f);
                    try { visible(false, "transition-alpha ancestor"); }
                    finally { transition.invoke(activity.parent, 1f); }
                } catch (ReflectiveOperationException error) { throw new IllegalStateException("TransitionSetter", error); }
                var decor = activity.getWindow().getDecorView();
                check(read(decor).extras().getInt("rootHashCode") == System.identityHashCode(decor), "decor identity");
                decor.setAlpha(0f);
                try {
                    var hiddenRoot = read(activity.code);
                    check(!hiddenRoot.extras().getBoolean("isVisibleToUser"), "normal visibility rejects decor alpha");
                    check(hiddenRoot.extras().getBoolean("isVisibleToUserIgnoreRoot"), "OEM decor exception");
                } finally { decor.setAlpha(1f); }
                TextView detached = new TextView(activity);
                check(!read(detached).extras().getBoolean("isVisibleToUserIgnoreRoot"), "detached is invisible");
                activity.code.setScaleX(0.5f); activity.code.setRotation(15f);
                try {
                    Rect expected = new Rect();
                    View.class.getMethod("getBoundsOnScreen", Rect.class).invoke(activity.code, expected);
                    Rect actual = read(activity.code).extras().getParcelable("getBoundsOnScreen", Rect.class);
                    check(expected.equals(actual), "transformed bounds from framework, no left/top approximation");
                } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                finally { activity.code.setScaleX(1f); activity.code.setRotation(0f); }
                android.graphics.drawable.ShapeDrawable drawable = new android.graphics.drawable.ShapeDrawable();
                drawable.setIntrinsicWidth(8); drawable.setIntrinsicHeight(8);
                activity.image.setImageDrawable(drawable);
                activity.image.setScaleType(ImageView.ScaleType.MATRIX);
                Matrix matrix = new Matrix(); matrix.setScale(2f, 3f); activity.image.setImageMatrix(matrix);
                var imageResult = read(activity.image);
                check("MATRIX".equals(imageResult.extras().getString("scaleType")), "image scale type");
                float[] values = imageResult.extras().getFloatArray("imageMatrix");
                check(values != null && values.length == 9 && values[0] == 2f && values[4] == 3f, "image matrix");
                View broken = new View(activity) {
                    @Override public boolean getGlobalVisibleRect(Rect rect, android.graphics.Point point) {
                        throw new IllegalStateException("synthetic bad View");
                    }
                };
                check("FAILED".equals(ColorOsViewMetadata.read(broken).status()), "View exception contained");
                dialog = new Dialog(activity);
                dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                TextView popup = new TextView(activity); popup.setText("Synthetic popup");
                dialog.setContentView(popup); dialog.show();
            });
            waitForIdleSync();
            main(() -> {
                View popupRoot = dialog.getWindow().getDecorView();
                check(read(popupRoot).extras().getBoolean("isVisibleToUserIgnoreRoot"), "attached dialog root");
                check(popupRoot.getWindowToken() != activity.code.getWindowToken(), "distinct dialog window");
                if (nativeChain) {
                    activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    try {
                        var multiple = snapshot();
                        check(multiple.roots().size() == 2, "activity and owned dialog captured");
                        check(text(multiple, false).contains("Synthetic popup"), "owned popup text captured");
                        dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                        var source = ColorOsNativeSnapshot.ticket(activity, 1);
                        check("SECURE_WINDOW".equals(ColorOsNativeSnapshot.capture(activity, source, 1).status()), "secure child window refuses whole snapshot");
                    } finally { activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); }
                }
                dialog.dismiss();
            });
            // Delayed same-page update is a synthetic scenario, NOT evidence that
            // OEM exit/back event delivery or real merchant E2E is connected.
            CountDownLatch changed = new CountDownLatch(1);
            main(() -> activity.code.postDelayed(() -> { activity.code.setText("Synthetic delayed code 107"); changed.countDown(); }, 180));
            check(changed.await(3, TimeUnit.SECONDS), "same-page delayed update arrived");
            main(() -> {
                visible(true, "delayed code remains visible");
                check("Synthetic delayed code 107".contentEquals(activity.code.getText()), "same-page updated text");
            });
            if (nativeChain) {
            main(this::snapshotChecks);
            // Feed ONLY text read from the actual synthetic View hierarchy into
            // the unmodified PCR. No direct injection into a publishing API.
            OriginalPcrFixture pcr = new OriginalPcrFixture(getTargetContext());
            Bundle parsed = pcr.extract(java.util.List.of(nativeText), "luckin", activity.getComponentName().getClassName());
            check(parsed.getStringArrayList("orderCodeList") != null
                    && parsed.getStringArrayList("orderCodeList").contains("107"), "live native text -> OEM PCR code");
            check("生椰拿铁".equals(parsed.getString("productName")), "live native text -> OEM PCR product");
            }
            if (windowInteraction) {
                phase = "window-delegate"; main(this::windowInteractionChecks);
                phase = "tracked-dom"; main(this::trackedDomChecks);
            }
            report.putBoolean("completed", true);
        } catch (Throwable error) {
            Throwable cause = failure == null ? error : failure;
            report.putString("failure", cause.getClass().getSimpleName() + ":" + cause.getMessage());
            report.putBoolean("completed", false);
        } finally {
            runOnMainSync(() -> {
                try { if (dialog != null && dialog.isShowing()) dialog.dismiss(); } catch (Throwable ignored) { }
                try { if (activity != null) activity.finish(); } catch (Throwable ignored) { }
            });
        }
        report.putInt("assertions", assertions);
        report.putString("capabilities", capabilities);
        report.putBoolean("native_chain", nativeChain);
        report.putString("scope", nativeChain ? "own live Views -> native selector -> original PCR; not merchant/notification end-to-end"
                : "own live metadata only; no hidden-API exemption required");
        finishOnce(report.getBoolean("completed") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
    }
    private void finishOnce(int result, Bundle report) {
        if (!finished.compareAndSet(false, true)) return;
        if (deadline != null) deadline.cancel();
        finish(result, report);
    }
    private ColorOsNativeSnapshot.Result snapshot() {
        var ticket = ColorOsNativeSnapshot.ticket(activity, 1);
        check(ticket != null, "snapshot constructor APIs available");
        var result = ColorOsNativeSnapshot.capture(activity, ticket, 1);
        check("OK".equals(result.status()), "native snapshot: " + result.status());
        check(result.nonce().equals(ticket.nonce()) && result.generation() == 1, "source identity receipt");
        return result;
    }
    private String text(ColorOsNativeSnapshot.Result result, boolean ignoreVisibility) {
        var extracted = ColorOsNativeExtractor.extractNodes(result.roots(), ignoreVisibility);
        check(extracted.fallbackVisibility() == 0, "all native nodes have original visibility metadata");
        return extracted.text();
    }
    private void snapshotChecks() {
        var ticket = ColorOsNativeSnapshot.ticket(activity, 1);
        check(ticket != null, "native ticket available: " + ColorOsNativeSnapshot.capabilities());
        check("SECURE_WINDOW".equals(ColorOsNativeSnapshot.capture(activity, ticket, 1).status()), "secure root refused");
        activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        try {
            check("STALE".equals(ColorOsNativeSnapshot.capture(activity, ticket, 2).status()), "generation change refused");
            var expired = new ColorOsNativeSnapshot.Ticket(ticket.component(), ticket.uid(), ticket.task(), ticket.token(),
                    ticket.generation(), android.os.SystemClock.elapsedRealtime() - 5001, ticket.nonce());
            check("STALE".equals(ColorOsNativeSnapshot.capture(activity, expired, 1).status()), "expired request refused");
            var wrongTask = new ColorOsNativeSnapshot.Ticket(ticket.component(), ticket.uid(), ticket.task() + 1, ticket.token(),
                    ticket.generation(), ticket.created(), ticket.nonce());
            check("STALE".equals(ColorOsNativeSnapshot.capture(activity, wrongTask, 1).status()), "different task refused");
            var wrongUid = new ColorOsNativeSnapshot.Ticket(ticket.component(), ticket.uid() + 1, ticket.task(), ticket.token(),
                    ticket.generation(), ticket.created(), ticket.nonce());
            check("STALE".equals(ColorOsNativeSnapshot.capture(activity, wrongUid, 1).status()), "different uid refused");
            var wrongToken = new ColorOsNativeSnapshot.Ticket(ticket.component(), ticket.uid(), ticket.task(), new android.os.Binder(),
                    ticket.generation(), ticket.created(), ticket.nonce());
            check("STALE".equals(ColorOsNativeSnapshot.capture(activity, wrongToken, 1).status()), "different window identity refused");
            var future = new ColorOsNativeSnapshot.Ticket(ticket.component(), ticket.uid(), ticket.task(), ticket.token(),
                    ticket.generation(), android.os.SystemClock.elapsedRealtime() + 60_000, ticket.nonce());
            check("STALE".equals(ColorOsNativeSnapshot.capture(activity, future, 1).status()), "future request refused");
            activity.code.setText("Synthetic full text\n".repeat(100) + "LAST-LINE-107");
            check(text(snapshot(), false).contains("LAST-LINE-107"), "multiline full text not viewport substring");
            activity.parent.setVisibility(View.INVISIBLE);
            try {
                var hidden = snapshot();
                check(!text(hidden, false).contains("LAST-LINE-107"), "ignoreVis=false prunes hidden native text");
                check(text(hidden, true).contains("LAST-LINE-107"), "ignoreVis=true preserves hidden native text");
            } finally { activity.parent.setVisibility(View.VISIBLE); }
            android.widget.EditText secret = new android.widget.EditText(activity);
            secret.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            secret.setText("SYNTHETIC-SECRET-987654");
            activity.parent.addView(secret, new android.widget.FrameLayout.LayoutParams(300, 60));
            try { check(!text(snapshot(), true).contains("SYNTHETIC-SECRET"), "password excluded before provider invocation"); }
            finally { activity.parent.removeView(secret); }
            android.widget.FrameLayout blocked = new android.widget.FrameLayout(activity);
            final int[] reads = {0};
            TextView forbidden = new TextView(activity) {
                @Override public void onProvideStructure(android.view.ViewStructure target) { reads[0]++; super.onProvideStructure(target); }
            };
            forbidden.setText("ASSIST-BLOCKED-SECRET"); blocked.addView(forbidden);
            activity.parent.addView(blocked, new android.widget.FrameLayout.LayoutParams(300, 100));
            try {
                View.class.getMethod("setAssistBlocked", boolean.class).invoke(blocked, true);
                check(!text(snapshot(), true).contains("ASSIST-BLOCKED-SECRET"), "framework assist-blocked subtree omitted");
                check(reads[0] == 0, "blocked child provider never invoked");
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("FixtureBlockSetup", error); }
            finally { activity.parent.removeView(blocked); }
            TextView hint = new TextView(activity); hint.setHint("SYNTHETIC-HINT-999");
            activity.parent.addView(hint, new android.widget.FrameLayout.LayoutParams(300, 60));
            try { check(!text(snapshot(), true).contains("SYNTHETIC-HINT"), "hint not substituted for empty text"); }
            finally { activity.parent.removeView(hint); }
            // Content is synthetic, actual source component stays our fixture.
            activity.code.setText("瑞幸咖啡\n等待取餐\n107\n取餐码\n生椰拿铁\n冰\n下单时间\n2026-09-06 20:00:00");
            nativeText = text(snapshot(), false);
            check(nativeText.contains("107") && nativeText.contains("生椰拿铁"), "native input captured for original PCR");
            OrderedFixture order = new OrderedFixture(activity);
            activity.parent.addView(order, new android.widget.FrameLayout.LayoutParams(300, 120));
            for (String name : java.util.List.of("ORDER-A", "ORDER-B", "ORDER-C")) {
                TextView item = new TextView(activity); item.setText(name); order.addView(item);
                item.layout(0, 0, 100, 30);
            }
            order.layout(0, 0, 300, 120);
            // FrameLayout's synchronous layout uses unmeasured child sizes (zero).
            // Give each synthetic node its final bounds after the parent layout.
            for (int i = 0; i < order.getChildCount(); i++) order.getChildAt(i).layout(0, 0, 100, 30);
            try {
                String normal = text(snapshot(), true);
                check(normal.indexOf("ORDER-A") < normal.indexOf("ORDER-C"), "disabled custom order ignored");
                order.enableOrder(true);
                String reverse = text(snapshot(), true);
                check(reverse.indexOf("ORDER-C") < reverse.indexOf("ORDER-A"), "enabled custom drawing order preserved");
                order.getChildAt(0).setZ(-2f);
                String elevated = text(snapshot(), true);
                check(elevated.indexOf("ORDER-A") < elevated.indexOf("ORDER-C"), "stable Z order before native traversal");
            } finally { activity.parent.removeView(order); }
        } finally { activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); }
    }
    private static final class OrderedFixture extends android.widget.FrameLayout {
        OrderedFixture(android.content.Context context) { super(context); }
        void enableOrder(boolean enabled) { setChildrenDrawingOrderEnabled(enabled); }
        @Override protected int getChildDrawingOrder(int count, int position) { return count - position - 1; }
    }
    private void windowInteractionChecks() {
        var window = activity.getWindow();
        var saved = window.getCallback();
        final int[] signals = {0}, touches = {0};
        final RuntimeException hostError = new IllegalStateException("synthetic-host-error");
        var host = (android.view.Window.Callback) java.lang.reflect.Proxy.newProxyInstance(
                android.view.Window.Callback.class.getClassLoader(), new Class<?>[]{android.view.Window.Callback.class},
                (p, method, args) -> {
                    if (method.getName().equals("dispatchTouchEvent")) { touches[0]++; return true; }
                    if (method.getName().equals("dispatchKeyEvent")) throw hostError;
                    if (method.getName().equals("onSearchRequested")) return false;
                    return null;
                });
        window.setCallback(host);
        var observer = io.github.mio.autopickupisland.coloros.ScopedWindowInteraction.install(window, () -> signals[0]++);
        var down = android.view.MotionEvent.obtain(0, 1, android.view.MotionEvent.ACTION_DOWN, 1f, 1f, 0);
        var up = android.view.MotionEvent.obtain(0, 2, android.view.MotionEvent.ACTION_UP, 1f, 1f, 0);
        var cancel = android.view.MotionEvent.obtain(0, 3, android.view.MotionEvent.ACTION_CANCEL, 1f, 1f, 0);
        try {
            check(observer != null && window.getCallback() != host, "public callback installed");
            var wrapper = window.getCallback();
            check(wrapper.dispatchTouchEvent(down) && signals[0] == 0, "down return preserved, no signal");
            check(wrapper.dispatchTouchEvent(up) && signals[0] == 1, "up delegated then signal");
            check(wrapper.dispatchTouchEvent(cancel) && signals[0] == 1 && touches[0] == 3, "cancel unchanged, each touch delegated once");
            check(!wrapper.onSearchRequested(), "unrelated callback return preserved");
            boolean sameError = false;
            try { wrapper.dispatchKeyEvent(null); } catch (RuntimeException error) { sameError = error == hostError; }
            check(sameError, "host exception preserved, no reflection wrapper");
            observer.close();
            check(window.getCallback() == host, "close restores exact original");
            wrapper.dispatchTouchEvent(up);
            check(signals[0] == 1, "retained wrapper disabled after close");
            observer = io.github.mio.autopickupisland.coloros.ScopedWindowInteraction.install(window, () -> { throw new NullPointerException("synthetic-module-error"); });
            check(window.getCallback().dispatchTouchEvent(up), "module exception cannot break host input");
            window.setCallback(saved);
            observer.close();
            check(window.getCallback() == saved, "never overwrite newer host callback");
            check(io.github.mio.autopickupisland.coloros.ScopedWindowInteraction.install(null, () -> {}) == null, "null window rejected");
            check(io.github.mio.autopickupisland.coloros.ScopedWindowInteraction.install(window, null) == null, "null listener rejected");
        } finally {
            if (observer != null) observer.close();
            window.setCallback(saved);
            down.recycle(); up.recycle(); cancel.recycle();
        }
    }
    public final class SilentWebView extends android.view.View {
        android.webkit.ValueCallback<String> callback;
        SilentWebView() { super(activity); }
        public void evaluateJavascript(String script, android.webkit.ValueCallback<String> callback) { this.callback = callback; }
    }
    private void trackedDomChecks() {
        SilentWebView view = new SilentWebView();
        activity.parent.addView(view, new android.widget.FrameLayout.LayoutParams(200, 60));
        view.layout(0, 0, 200, 60);
        var client = new io.github.mio.autopickupisland.coloros.ColorOsWebViewClient();
        int[] calls = {0};
        String[] result = {""};
        var selection = new io.github.mio.autopickupisland.coloros.ColorOsWebViewSelector.Options(
                java.util.List.of(view.getClass().getName()), "evaluateJavascript", true, true, false);
        try {
            var ticket = io.github.mio.autopickupisland.coloros.ColorOsActivityCapture.requestTracked(activity,
                    client, selection, io.github.mio.autopickupisland.coloros.ColorOsWebViewClient.Request.pageId(),
                    () -> true, value -> { calls[0]++; result[0] = value.status().name(); });
            check(ticket != null && view.callback != null, "tracked real-window request owns ticket");
            check(calls[0] == 0, "silent renderer remains pending");
            var late = view.callback;
            ticket.cancel();
            check(calls[0] == 1 && result[0].equals("CANCELLED"), "cancel immediately drains batch");
            late.onReceiveValue("[]");
            check(calls[0] == 1, "late callback cannot complete cancelled request");
            var next = io.github.mio.autopickupisland.coloros.ColorOsActivityCapture.requestTracked(activity,
                    client, selection, io.github.mio.autopickupisland.coloros.ColorOsWebViewClient.Request.pageId(),
                    () -> true, value -> { calls[0]++; result[0] = value.status().name(); });
            check(next != null && view.callback != late, "next request gets independent callback");
            view.callback.onReceiveValue("[]");
            check(calls[0] == 2 && result[0].equals("OK"), "next request completes without old timeout");
            next.cancel();
            check(calls[0] == 2, "cancel completed ticket is idempotent");
            late.onReceiveValue("[123]");
            check(calls[0] == 2 && result[0].equals("OK"), "old renderer cannot overwrite new result");
        } finally { client.close(); activity.parent.removeView(view); }
    }
}
