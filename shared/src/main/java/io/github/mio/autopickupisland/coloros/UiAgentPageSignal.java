package io.github.mio.autopickupisland.coloros;

import android.os.*;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** OS4 transport adaptation: metadata-only signal, NOT OEM recognition or arbitrary JS.
 * Capability is installed only by an authorized, exact-route content request.
 */
public final class UiAgentPageSignal extends Binder {
    public static final String KEY = "mio.pageSignal", NONCE = "mio.pageSignalNonce";
    private static final String DESCRIPTOR = "io.github.mio.coloros.PageSignal.v1";
    private final int uid;
    private final String nonce = UUID.randomUUID().toString();
    private final BooleanSupplier current;
    private final Runnable changed;
    public UiAgentPageSignal(int uid, BooleanSupplier current, Runnable changed) {
        this.uid = uid; this.current = current; this.changed = changed;
    }
    public void attach(Bundle request) { request.putBinder(KEY, this); request.putString(NONCE, nonce); }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(DESCRIPTOR); return true; }
        if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
        try {
            if (Binder.getCallingUid() != uid) return true;
            data.enforceInterface(DESCRIPTOR);
            String token = data.readString();
            long age = SystemClock.elapsedRealtime() - data.readLong();
            data.enforceNoDataAvail();
            if (nonce.equals(token) && age >= 0 && age <= 5_000 && current.getAsBoolean()) changed.run();
        } catch (Throwable ignored) { }
        return true;
    }
    public static boolean send(IBinder callback, String nonce) {
        if (callback == null || nonce == null || !nonce.matches("[a-f0-9-]{36}")) return false;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeString(nonce);
            data.writeLong(SystemClock.elapsedRealtime());
            return callback.transact(FIRST_CALL_TRANSACTION, data, null, FLAG_ONEWAY);
        } catch (Throwable ignored) { return false; }
        finally { data.recycle(); }
    }
}
