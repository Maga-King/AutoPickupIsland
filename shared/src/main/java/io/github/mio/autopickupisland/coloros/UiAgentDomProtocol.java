package io.github.mio.autopickupisland.coloros;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SharedMemory;
import android.os.SystemClock;
import android.system.OsConstants;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** OS4 host adaptation, NOT an original ColorOS Binder protocol.
 * Stock SecurityManager/DataHub retain caller authorization and target routing.
 * This typed, read-only extension transports original JS results via read-only
 * shared memory. It does not itself arrange execution inside another application.
 */
public final class UiAgentDomProtocol {
    public static final String CLIENT_DESCRIPTOR = "miui.security.IUIAgentInteractionClient";
    public static final String CALLBACK_DESCRIPTOR = "miui.security.IUIAgentCallback";
    public static final String VERSION_KEY = "mio.coloros.protocol";
    public static final String NONCE_KEY = "mio.requestId";
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 1_048_576;

    public record Target(String packageName, int uid, String activity) {
        public Target {
            if (packageName == null || !packageName.matches("[A-Za-z0-9_.]+") || uid < 10_000
                    || activity == null || activity.isEmpty() || activity.length() > 512)
                throw new IllegalArgumentException("InvalidTarget");
        }
        public String token() { return packageName + "~" + uid; }
    }
    public record Request(Target target, String nonce, Bundle bundle) { }
    public record Reply(boolean ok, String status, String raw, String timing) {
        public Reply(boolean ok, String status, String raw) { this(ok, status, raw, ""); }
    }
    private UiAgentDomProtocol() { }

    public static Request request(Target target, ColorOsWebViewClient.Request options) {
        return request(target, options, new ColorOsWebViewSelector.Options(List.of(), "", true, false, false));
    }

    /** Transport resolved OEM selection data, never arbitrary JS or a remotely supplied class loader. */
    public static Request request(Target target, ColorOsWebViewClient.Request options,
                                  ColorOsWebViewSelector.Options selection) {
        Bundle data = new Bundle();
        String nonce = UUID.randomUUID().toString();
        data.putInt(VERSION_KEY, VERSION);
        data.putString(NONCE_KEY, nonce);
        data.putInt("uiAgentType", 0);
        data.putInt("catch_way", -1);
        data.putStringArray("packageNames", new String[]{target.token()}); // NEVER empty/all-app capture.
        data.putString("TargetActivity", target.activity());
        data.putLong("mio.createdElapsed", SystemClock.elapsedRealtime());
        data.putInt("mio.resultType", options.resultType());
        data.putStringArrayList("mio.routes", new ArrayList<>(options.routes()));
        data.putBoolean("mio.alipay", options.alipay());
        data.putBoolean("mio.labelPathChange", options.labelPathChange());
        data.putBoolean("mio.rootPortal", options.rootPortal());
        data.putBoolean("mio.leafNodes", options.leafNodes());
        data.putStringArrayList("mio.webClasses", new ArrayList<>(selection.classes()));
        data.putString("mio.evaluateMethod", selection.evaluateMethod());
        data.putBoolean("mio.lastOnly", selection.lastOnly());
        data.putBoolean("mio.ignoreVisible", selection.ignoreVisible());
        data.putBoolean("mio.enableWebGroup", selection.enableWebGroup());
        return new Request(target, nonce, data);
    }

    public static boolean validRequest(Bundle data, Target actual) {
        if (data == null || data.getInt(VERSION_KEY, 0) != VERSION || data.getInt("uiAgentType", -1) != 0) return false;
        String[] targets = data.getStringArray("packageNames");
        String nonce = data.getString(NONCE_KEY, "");
        long age = SystemClock.elapsedRealtime() - data.getLong("mio.createdElapsed", -1);
        int type = data.getInt("mio.resultType", -1);
        return targets != null && targets.length == 1 && actual.token().equals(targets[0])
                && actual.activity().equals(data.getString("TargetActivity"))
                && nonce != null && nonce.matches("[a-f0-9-]{36}") && age >= 0 && age <= 5_000L && type >= 0 && type <= 5
                && boundedOptions(data);
    }

    // Host IPC bounds are intentionally separate from OEM XML semantics. Reject
    // malformed or oversized inputs before starting the lazy original-code load.
    private static boolean boundedOptions(Bundle data) {
        try {
            for (String key : List.of("mio.routes", "mio.webClasses")) {
                Object value = data.get(key);
                if (value == null) continue; // Old version-1 requests omitted selection fields.
                if (!(value instanceof ArrayList<?> entries) || entries.size() > 256) return false;
                int chars = 0;
                for (Object item : entries) {
                    if (!(item instanceof String text) || (chars += text.length()) > 65_536) return false;
                }
            }
            Object method = data.get("mio.evaluateMethod");
            if (method != null && (!(method instanceof String name) || name.length() > 128)) return false;
            for (String key : List.of("mio.alipay", "mio.labelPathChange", "mio.rootPortal", "mio.leafNodes",
                    "mio.lastOnly", "mio.ignoreVisible", "mio.enableWebGroup")) {
                Object value = data.get(key);
                if (value != null && !(value instanceof Boolean)) return false;
            }
            return true;
        } catch (Throwable ignored) { return false; }
    }

    public static ColorOsWebViewSelector.Options selection(Bundle request) {
        return new ColorOsWebViewSelector.Options(request.getStringArrayList("mio.webClasses"),
                request.getString("mio.evaluateMethod", ""), request.getBoolean("mio.lastOnly", true),
                request.getBoolean("mio.ignoreVisible", false), request.getBoolean("mio.enableWebGroup", false));
    }

    public static ColorOsWebViewClient.Request options(Bundle request) {
        return new ColorOsWebViewClient.Request(request.getInt("mio.resultType", -1),
                request.getStringArrayList("mio.routes"), request.getBoolean("mio.alipay"),
                request.getBoolean("mio.labelPathChange"), request.getBoolean("mio.rootPortal"),
                request.getBoolean("mio.leafNodes", true));
    }

    /** Bounded nonblocking Binder write: contents are shared memory, not a pipe or inline megabyte. */
    public static boolean send(IBinder callback, Target actual, String nonce, String status, String raw) {
        return send(callback, actual, nonce, status, raw, "");
    }
    public static boolean send(IBinder callback, Target actual, String nonce, String status, String raw, String reader) {
        return send(callback, actual, nonce, status, raw, reader, "");
    }
    public static boolean send(IBinder callback, Target actual, String nonce, String status, String raw, String reader, String timing) {
        if (callback == null) return false;
        SharedMemory memory = null;
        Parcel parcel = null;
        ByteBuffer mapped = null;
        try {
            Bundle reply = new Bundle();
            reply.putInt(VERSION_KEY, VERSION);
            reply.putString(NONCE_KEY, nonce);
            reply.putString("token", actual.token());
            reply.putString("TargetActivity", actual.activity());
            if (reader != null && !reader.isEmpty() && reader.length() <= 128) reply.putString("mio.reader", reader);
            if (validTiming(timing)) reply.putString("mio.timing", timing);
            if ("OK".equals(status)) {
                byte[] bytes = raw == null ? new byte[0] : raw.getBytes(StandardCharsets.UTF_8);
                if (bytes.length == 0 || bytes.length > MAX_BYTES) status = "LIMIT";
                else {
                    memory = SharedMemory.create("mio-fixture-dom", bytes.length);
                    mapped = memory.mapReadWrite();
                    mapped.put(bytes);
                    SharedMemory.unmap(mapped); mapped = null;
                    if (!memory.setProtect(OsConstants.PROT_READ)) throw new IllegalStateException("ReadOnlyMemory");
                    reply.putParcelable("mio.content", memory);
                    reply.putInt("mio.byteCount", bytes.length);
                }
            }
            reply.putString("mio.status", status);
            reply.putInt("code", "OK".equals(status) ? 0 : 1);
            parcel = Parcel.obtain(callback);
            parcel.writeInterfaceToken(CALLBACK_DESCRIPTOR);
            parcel.writeTypedObject(reply, 0);
            return callback.transact(IBinder.FIRST_CALL_TRANSACTION, parcel, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (mapped != null) try { SharedMemory.unmap(mapped); } catch (Throwable ignored) { }
            if (memory != null) try { memory.close(); } catch (Throwable ignored) { }
            if (parcel != null) try { parcel.recycle(); } catch (Throwable ignored) { }
        }
    }

    /** Validates identity/nonce BEFORE mapping; always releases foreign or failed reply memory too. */
    public static Reply read(Bundle data, Request expected) {
        SharedMemory memory = null;
        ByteBuffer mapped = null;
        try {
            if (data == null) return new Reply(false, "NO_REPLY", "");
            memory = data.getParcelable("mio.content", SharedMemory.class);
            if (data.getInt(VERSION_KEY, 0) != VERSION || expected == null
                    || !expected.nonce.equals(data.getString(NONCE_KEY))
                    || !expected.target.token().equals(data.getString("token"))
                    || !expected.target.activity().equals(data.getString("TargetActivity")))
                return new Reply(false, "IDENTITY_MISMATCH", "");
            String status = data.getString("mio.status", "NO_STATUS");
            String timing = data.getString("mio.timing", "");
            if (!validTiming(timing)) timing = "";
            if (!"OK".equals(status) || data.getInt("code", -1) != 0) return new Reply(false, status, "", timing);
            int length = data.getInt("mio.byteCount", -1);
            if (memory == null || length <= 0 || length > MAX_BYTES || memory.getSize() != length)
                return new Reply(false, "INVALID_SIZE", "");
            mapped = memory.mapReadOnly();
            String raw = StandardCharsets.UTF_8.newDecoder().decode(mapped).toString();
            return new Reply(true, "OK", raw, timing);
        } catch (Throwable ignored) {
            return new Reply(false, "INVALID_REPLY", "");
        } finally {
            if (mapped != null) try { SharedMemory.unmap(mapped); } catch (Throwable ignored) { }
            if (memory != null) try { memory.close(); } catch (Throwable ignored) { }
        }
    }
    private static boolean validTiming(String timing) {
        return timing != null && timing.length() <= 120
                && timing.matches("type=[0-5];stage=(LOAD|READ|DONE|PAUSE);queue=[0-9]{1,6};source=[0-9]{1,6}");
    }
}
