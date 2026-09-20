package com.miui.contentcatcher;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import io.github.mio.autopickupisland.coloros.ColorOsActivityCapture;
import io.github.mio.autopickupisland.coloros.ColorOsWebViewClient;
import io.github.mio.autopickupisland.coloros.UiAgentDomProtocol;
import io.github.mio.autopickupisland.coloros.OriginalColorOsWebReader;
import io.github.mio.autopickupisland.coloros.ColorOsCollectorScope;
import io.github.mio.autopickupisland.coloros.UiAgentPageSignal;
import io.github.mio.autopickupisland.coloros.UiAgentNativeProtocol;
import io.github.mio.autopickupisland.coloros.ScopedWindowInteraction;
import java.util.concurrent.CompletableFuture;
import miui.contentcatcher.IInterceptor;
import miui.security.IUIAgentCallback;

/** InterceptorFactory-compatible reader, shared by the integrated APK and legacy carrier.
 * Host, Activity, request identity and lifetime gates apply before any read.
 * The unmodified installed ContentCatcher handles every ordinary request/lifecycle.
 */
public final class Interceptor implements IInterceptor {
    private static final String FIXTURE = "io.github.mio.autopickupfixture";
    private final Activity activity;
    private final IInterceptor original;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ColorOsWebViewClient client; // Main-thread confined, lazy original backend only.
    private Job pending;
    private IBinder pageSignal;
    private String pageSignalNonce;
    private ScopedWindowInteraction windowInteraction;
    private final Runnable pageChanged = () -> {
        if (this.active && !this.destroyed && !UiAgentPageSignal.send(pageSignal, pageSignalNonce)) clearPageSignal();
    };
    private static CompletableFuture<OriginalColorOsWebReader> readerFuture;
    private final UiAgentDomProtocol.Target target;
    private volatile boolean active, destroyed;
    private volatile int generation;
    private volatile String diagnostic = "CREATED";
    private Object uiAgentClient; // Binder receiver has only a weak owner reference.
    private static final class ClientApi {
        static final ClientApi INSTANCE = create();
        final Class<?> listener = Class.forName(UiAgentDomProtocol.CLIENT_DESCRIPTOR);
        final Class<?> manager = Class.forName("miui.contentcatcher.sdk.ContentCatcherManager");
        final java.lang.reflect.Method register = manager.getMethod("registerUIAgentListener", String.class, listener);
        final java.lang.reflect.Method unregister = manager.getMethod("unregisterUIAgentListener", String.class);
        final java.lang.reflect.Method callback = Class.forName(UiAgentDomProtocol.CALLBACK_DESCRIPTOR + "$Stub")
                .getMethod("asInterface", IBinder.class);
        final Object service = manager.getMethod("getInstance").invoke(null);
        ClientApi() throws Exception { }
        static ClientApi create() { try { return new ClientApi(); } catch (Throwable ignored) { return null; } }
    }
    private static final class ClientBinder extends android.os.Binder {
        final java.lang.ref.WeakReference<Interceptor> owner;
        ClientBinder(Interceptor owner) { this.owner = new java.lang.ref.WeakReference<>(owner); }
        @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(UiAgentDomProtocol.CLIENT_DESCRIPTOR); return true;
            }
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            try {
                data.enforceInterface(UiAgentDomProtocol.CLIENT_DESCRIPTOR);
                int uid = android.os.Binder.getCallingUid();
                if (uid != 1000 && uid != 0) throw new SecurityException("SystemOnly");
                Bundle params = data.readTypedObject(Bundle.CREATOR);
                IBinder callback = data.readStrongBinder(); data.enforceNoDataAvail();
                Interceptor target = owner.get();
                if (target != null && ClientApi.INSTANCE != null)
                    target.onUiAgent(params, (IUIAgentCallback) ClientApi.INSTANCE.callback.invoke(null, callback));
            } catch (Throwable ignored) { }
            if (reply != null) reply.writeNoException();
            return true;
        }
    }
    private void registerUiAgentClient(int version) {
        if (!active || destroyed || generation != version || ClientApi.INSTANCE == null) return;
        if (!ColorOsCollectorScope.nativePackageAllowed(target.packageName())
                && !ColorOsCollectorScope.activityAllowed(target.packageName(), target.activity())) return;
        try {
            ClientApi api = ClientApi.INSTANCE;
            if (uiAgentClient == null) {
                ClientBinder binder = new ClientBinder(this);
                uiAgentClient = java.lang.reflect.Proxy.newProxyInstance(api.listener.getClassLoader(),
                        new Class<?>[]{api.listener}, (proxy, method, args) -> switch (method.getName()) {
                            case "asBinder" -> binder;
                            case "toString" -> "MioScopedUiAgentClient";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> args != null && args.length == 1 && args[0] == proxy;
                            default -> null;
                        });
            }
            // OS4 DataHub refuses duplicate tokens (first registration wins).
            // The stock SettingTrigger can win the race against the framework
            // proxy on resume; another register alone cannot replace its XML-only
            // client. Replace ONLY this host's package~UID after the delegate.
            // Both methods were resolved before mutation; ordinary requests still
            // pass through original.onUiAgent. No polling or generated-name hook.
            api.unregister.invoke(api.service, target.token());
            api.register.invoke(api.service, target.token(), uiAgentClient);
        } catch (Throwable ignored) { diagnostic = "REGISTER_UNAVAILABLE"; }
    }
    public Interceptor(Activity activity) throws Exception {
        if (activity == null || !ColorOsCollectorScope.packageAllowed(activity.getPackageName())) throw new SecurityException("HostNotAllowed");
        this.activity = activity;
        target = new UiAgentDomProtocol.Target(activity.getPackageName(), activity.getApplicationInfo().uid, activity.getClass().getName());
        Context systemComponent = activity.createPackageContext("com.miui.contentcatcher", Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        Class<?> stock = Class.forName("com.miui.contentcatcher.Interceptor", true, systemComponent.getClassLoader());
        if (stock == Interceptor.class || stock.getClassLoader() == getClass().getClassLoader()) throw new IllegalStateException("RecursiveDelegate");
        original = (IInterceptor) stock.getConstructor(Activity.class).newInstance(activity);
    }
    public String diagnosticStatus() { return diagnostic; }
    public String captureCapabilities() { return ColorOsActivityCapture.capabilities(); }
    private static synchronized CompletableFuture<OriginalColorOsWebReader> reader(Context fixture) {
        if (readerFuture == null) {
            CompletableFuture<OriginalColorOsWebReader> future = new CompletableFuture<>();
            readerFuture = future; // One load attempt per process, no automatic retry loop.
            try {
                new Thread(() -> {
                    try {
                        Context assets = fixture.createPackageContext(CarrierAssetOwner.PACKAGE, 0);
                        future.complete(OriginalColorOsWebReader.loadForCarrier(fixture, assets));
                    } catch (Throwable error) { future.completeExceptionally(error); }
                }, "MioColorOsReaderLoad").start();
            } catch (Throwable error) { future.completeExceptionally(error); }
        }
        return readerFuture;
    }
    @Override public void onUiAgent(Bundle params, IUIAgentCallback callback) {
        try {
            if (params != null && params.containsKey(UiAgentNativeProtocol.KEY)) {
                Bundle request = new Bundle(params);
                main.post(() -> {
                    try { diagnostic = UiAgentNativeProtocol.capture(activity, request,
                            callback == null ? null : callback.asBinder(),
                            () -> active && !destroyed, () -> generation);
                        if (active && !destroyed && ("OK".equals(diagnostic) || "EMPTY".equals(diagnostic)
                                || "WORK_LIMIT".equals(diagnostic))
                                && UiAgentNativeProtocol.valid(request, target, activity.getTaskId())) installPageSignal(request);
                    }
                    catch (Throwable ignored) { diagnostic = "NATIVE_FAILED"; }
                });
                return;
            }
        } catch (Throwable ignored) { return; }
        boolean extension;
        try { extension = params != null && params.containsKey(UiAgentDomProtocol.VERSION_KEY); }
        catch (Throwable ignored) { return; }
        if (!extension) { guarded(() -> original.onUiAgent(params, callback)); return; }
        // Malformed extension requests MUST NOT fall through into stock capture.
        try { main.post(() -> capture(params, callback)); } catch (Throwable ignored) { }
    }
    private void capture(Bundle params, IUIAgentCallback callback) {
        String nonce = "";
        Job owned = null;
        try {
            nonce = params.getString(UiAgentDomProtocol.NONCE_KEY, "");
            if (!active || destroyed || !ColorOsCollectorScope.activityAllowed(target.packageName(), target.activity())
                    || !UiAgentDomProtocol.validRequest(params, target)) { reply(callback, nonce, "REJECTED", ""); return; }
            if (pending != null) { reply(callback, nonce, "BUSY", ""); return; }
            owned = new Job(new Bundle(params), callback, nonce, generation);
            pending = owned;
            Job job = owned;
            long remaining = 5_000L - (SystemClock.elapsedRealtime() - params.getLong("mio.createdElapsed", -1));
            if (remaining <= 0) { finish(job, "TIMEOUT", ""); return; }
            if (!main.postDelayed(job.timeout, remaining)) { finish(job, "FAILED", ""); return; }
            if (client != null) { begin(job); return; }
            reader(activity.getApplicationContext()).whenComplete((loaded, error) -> {
                try {
                    main.post(() -> {
                        try {
                            if (pending != job || job.done) return;
                            if (error != null || loaded == null) { finish(job, "UNSUPPORTED", ""); return; }
                            if (!current(job)) { finish(job, "STALE", ""); return; }
                            client = new ColorOsWebViewClient(5_000, loaded);
                            begin(job);
                        } catch (Throwable ignored) { finish(job, "FAILED", ""); }
                    });
                } catch (Throwable ignored) { /* Main deadline owns cleanup if posting fails. */ }
            });
        } catch (Throwable ignored) {
            if (owned != null) finish(owned, "FAILED", "");
            else reply(callback, nonce, "FAILED", ""); // Never release another request's slot.
        }
    }
    private final class Job {
        Bundle params;
        IUIAgentCallback callback;
        final String nonce;
        final int version;
        final long accepted = SystemClock.elapsedRealtime();
        final long queued;
        final int type;
        String stage = "LOAD";
        ColorOsWebViewClient.Ticket ticket;
        boolean done;
        final Runnable timeout = () -> finish(this, "TIMEOUT", "");
        Job(Bundle params, IUIAgentCallback callback, String nonce, int version) {
            this.params = params; this.callback = callback; this.nonce = nonce; this.version = version;
            queued = Math.max(0, Math.min(999999, accepted - params.getLong("mio.createdElapsed", accepted)));
            type = params.getInt("mio.resultType", -1);
        }
    }
    private boolean current(Job job) {
        return !job.done && pending == job && active && !destroyed && generation == job.version
                && job.params != null && UiAgentDomProtocol.validRequest(job.params, target);
    }
    private void begin(Job job) {
        if (!current(job)) { finish(job, "STALE", ""); return; }
        job.stage = "READ";
        var ticket = ColorOsActivityCapture.requestTracked(activity, client,
                UiAgentDomProtocol.selection(job.params),
                UiAgentDomProtocol.options(job.params), () -> current(job),
                result -> { if (!job.done) { job.stage = "DONE"; finish(job, result.status().name(), result.raw()); } });
        // A synchronous callback may have already finished this job.
        if (job.done) { if (ticket != null) ticket.cancel(); }
        else job.ticket = ticket;
    }
    private void finish(Job job, String status, String raw) {
        if (job == null || job.done) return;
        job.done = true;
        main.removeCallbacks(job.timeout);
        if (pending == job) pending = null;
        var ticket = job.ticket; job.ticket = null;
        if (ticket != null) ticket.cancel();
        if ("OK".equals(status) && currentSignalJob(job)) {
            installPageSignal(job.params);
        }
        IUIAgentCallback callback = job.callback;
        job.callback = null; job.params = null;
        if ("OK".equals(status) && (!active || destroyed || generation != job.version)) {
            status = "STALE"; raw = "";
        }
        reply(callback, job.nonce, status, raw, "type=" + job.type + ";stage=" + job.stage
                + ";queue=" + job.queued + ";source=" + Math.min(999999, SystemClock.elapsedRealtime() - job.accepted));
    }
    private boolean currentSignalJob(Job job) {
        return active && !destroyed && generation == job.version && job.params != null
                && job.params.getInt("mio.resultType", -1) == 4
                && UiAgentDomProtocol.validRequest(job.params, target);
    }
    private void clearPageSignal() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::clearPageSignal); return; }
        pageSignal = null; pageSignalNonce = null; main.removeCallbacks(pageChanged);
        if (windowInteraction != null) { windowInteraction.close(); windowInteraction = null; }
    }
    private void installPageSignal(Bundle params) {
        IBinder signal = params.getBinder(UiAgentPageSignal.KEY);
        String nonce = params.getString(UiAgentPageSignal.NONCE, "");
        if (signal != null && nonce != null && nonce.matches("[a-f0-9-]{36}")) {
            pageSignal = signal; pageSignalNonce = nonce;
            if (windowInteraction == null) windowInteraction = ScopedWindowInteraction.install(activity.getWindow(), this::schedulePageChanged);
        }
    }
    private void schedulePageChanged() {
        if (!active || destroyed || pageSignal == null) return;
        main.removeCallbacks(pageChanged);
        main.postDelayed(pageChanged, 350L);
        main.postDelayed(pageChanged, 1_200L);
    }
    private void reply(IUIAgentCallback callback, String nonce, String status, String raw) {
        reply(callback, nonce, status, raw, "");
    }
    private void reply(IUIAgentCallback callback, String nonce, String status, String raw, String timing) {
        diagnostic = status;
        try { if (callback != null) UiAgentDomProtocol.send(callback.asBinder(), target, nonce, status, raw,
                client == null ? "NOT_LOADED" : "ORIGINAL:" + OriginalColorOsWebReader.FRAMEWORK_SHA256, timing); }
        catch (Throwable ignored) { diagnostic = "SEND_FAILED"; }
    }
    private void guarded(Runnable action) { try { action.run(); } catch (Throwable ignored) { diagnostic = "STOCK_FAILED"; } }
    @Override public boolean dispatchKeyEvent(KeyEvent e, View v, Activity a) { try { return original.dispatchKeyEvent(e, v, a); } catch (Throwable ignored) { return false; } }
    @Override public boolean dispatchTouchEvent(MotionEvent e, View v, Activity a) {
        try {
            if (active && !destroyed && pageSignal != null && a == activity && e != null
                    && e.getActionMasked() == MotionEvent.ACTION_UP) {
                // Two coalesced post-interaction signals; no idle timer or touch/page payload.
                schedulePageChanged();
            }
        } catch (Throwable ignored) { }
        try { return original.dispatchTouchEvent(e, v, a); } catch (Throwable ignored) { return false; }
    }
    @Override public void notifyActivityCreate() { guarded(original::notifyActivityCreate); }
    @Override public void notifyActivityStart() { guarded(original::notifyActivityStart); }
    @Override public void notifyActivityResume() {
        active = true; int version = ++generation;
        guarded(original::notifyActivityResume);
        guarded(() -> main.post(() -> registerUiAgentClient(version)));
    }
    @Override public void notifyActivityPause() {
        active = false; int pausedGeneration = ++generation;
        clearPageSignal();
        guarded(() -> main.post(() -> {
            // A later resume/read may already own the slot; never cancel that job.
            Job old = pending;
            if (old != null && old.version < pausedGeneration) {
                old.stage = "PAUSE"; finish(old, "STALE", "");
            }
        }));
        guarded(original::notifyActivityPause);
    }
    @Override public void notifyActivityStop() { guarded(original::notifyActivityStop); }
    @Override public void notifyActivityDestroy() {
        active = false; destroyed = true; generation++;
        uiAgentClient = null;
        clearPageSignal();
        guarded(() -> main.post(() -> {
            finish(pending, "STALE", "");
            if (client != null) guarded(client::close);
            main.removeCallbacksAndMessages(null);
        }));
        guarded(original::notifyActivityDestroy);
    }
    @Override public void notifyWebView(View view, boolean value) { guarded(() -> original.notifyWebView(view, value)); }
    @Override public void processRequest(Uri uri) { guarded(() -> original.processRequest(uri)); }
}
