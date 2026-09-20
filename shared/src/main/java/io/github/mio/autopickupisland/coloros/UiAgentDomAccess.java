package io.github.mio.autopickupisland.coloros;

import android.os.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Single read on a short-lived worker. The OS UIAgent service retains caller
 * authorization. Never called from main/Binder callbacks or an idle polling loop.
 */
public final class UiAgentDomAccess {
    private UiAgentDomAccess() { }
    public static UiAgentDomProtocol.Reply read(UiAgentDomProtocol.Target target,
            ColorOsWebViewClient.Request options, ColorOsWebViewSelector.Options selection,
            BooleanSupplier current) {
        return read(target, options, selection, current, null);
    }
    public static UiAgentDomProtocol.Reply read(UiAgentDomProtocol.Target target,
            ColorOsWebViewClient.Request options, ColorOsWebViewSelector.Options selection,
            BooleanSupplier current, UiAgentPageSignal signal) {
        try {
            var request = UiAgentDomProtocol.request(target, options, selection);
            if (signal != null && options.resultType() == 4) signal.attach(request.bundle());
            return exchange(request, current, "ORIGINAL:" + OriginalColorOsWebReader.FRAMEWORK_SHA256);
        } catch (Throwable ignored) { return failed("UNAVAILABLE"); }
    }
    /** Same OS-authorized transport, distinct typed native backend. */
    static UiAgentDomProtocol.Reply exchange(UiAgentDomProtocol.Request request,
            BooleanSupplier current, String backend) {
        if (Looper.myLooper() == Looper.getMainLooper()) return failed("WORKER_REQUIRED");
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<UiAgentDomProtocol.Reply> output = new AtomicReference<>(failed("TIMEOUT"));
        try {
            if (current == null || !current.getAsBoolean()) return failed("STALE");
            var target = request.target();
            Binder binder = new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                    if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(UiAgentDomProtocol.CALLBACK_DESCRIPTOR); return true; }
                    if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                    Bundle result = null;
                    boolean decoded = false;
                    boolean accepted = false;
                    try {
                        data.enforceInterface(UiAgentDomProtocol.CALLBACK_DESCRIPTOR);
                        result = data.readTypedObject(Bundle.CREATOR); data.enforceNoDataAvail();
                        if (Binder.getCallingUid() != target.uid()) return true;
                        if (!finished.compareAndSet(false, true)) return true;
                        accepted = true;
                        var received = UiAgentDomProtocol.read(result, request); decoded = true;
                        if (!current.getAsBoolean()) received = failed("STALE");
                        else if ((received.ok() || UiAgentNativeProtocol.BACKEND.equals(backend))
                                && !backend.equals(result.getString("mio.reader"))) received = failed("WRONG_BACKEND");
                        output.set(received);
                    } catch (Throwable ignored) {
                        if (!accepted && Binder.getCallingUid() == target.uid())
                            accepted = finished.compareAndSet(false, true);
                        if (accepted) output.set(failed("INVALID_REPLY"));
                    }
                    finally {
                        if (!decoded && result != null) UiAgentDomProtocol.read(result, null);
                        // Duplicate/foreign callbacks cannot finish a different callback's decode.
                        if (accepted) done.countDown();
                    }
                    return true;
                }
            };
            Class<?> callbackType = Class.forName(UiAgentDomProtocol.CALLBACK_DESCRIPTOR);
            Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, (proxy, method, args) -> switch (method.getName()) {
                case "asBinder" -> binder;
                case "toString" -> "MioReadOnlyDomCallback";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> args != null && args.length == 1 && proxy == args[0];
                default -> null;
            });
            Class<?> manager = Class.forName("miui.contentcatcher.sdk.ContentCatcherManager");
            Object service = manager.getMethod("getInstance").invoke(null);
            manager.getMethod("onUIAgentEvent", Bundle.class, callbackType).invoke(service, request.bundle(), callback);
            if (!done.await(5_500L, TimeUnit.MILLISECONDS)) { finished.set(true); return failed("TIMEOUT"); }
            return current.getAsBoolean() ? output.get() : failed("STALE");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return failed("CANCELLED");
        } catch (Throwable ignored) { return failed("UNAVAILABLE"); }
        finally { finished.set(true); }
    }
    private static UiAgentDomProtocol.Reply failed(String status) { return new UiAgentDomProtocol.Reply(false, status, ""); }
}
