package io.github.mio.autopickupisland.coloros;

import android.app.Activity;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import io.github.mio.autopickupisland.ColorOsNativeExtractor;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** OS4 native adapter. Shares only the bounded, read-only UIAgent envelope with
 * DOM; no script, tree objects, View references or remote class names on the wire. */
public final class UiAgentNativeProtocol {
    public static final String KEY = "mio.coloros.native.protocol";
    public static final String BACKEND = "NATIVE_SNAPSHOT:1";
    private static final String TASK = "mio.native.task", GENERATION = "mio.native.generation";
    public record Result(String status, String text, int visited, long sourceGeneration) {
        public boolean ok() { return "OK".equals(status); }
    }
    private UiAgentNativeProtocol() { }

    public static UiAgentDomProtocol.Request request(UiAgentDomProtocol.Target target, int task,
            long generation, boolean ignoreVisibility) {
        if (task < 0 || generation < 0) throw new IllegalArgumentException("InvalidNativeIdentity");
        var request = UiAgentDomProtocol.request(target,
                ColorOsWebViewClient.Request.content(List.of(), false, false, false));
        request.bundle().putInt(KEY, 1);
        request.bundle().putInt("mio.resultType", -100); // Older DOM readers reject, never execute JS.
        request.bundle().putInt(TASK, task);
        request.bundle().putLong(GENERATION, generation);
        request.bundle().putBoolean("mio.ignoreVisible", ignoreVisibility);
        return request;
    }
    public static boolean valid(Bundle data, UiAgentDomProtocol.Target actual, int task) {
        try {
            if (data == null || data.getInt(KEY, 0) != 1 || data.getInt("mio.resultType", 0) != -100
                    || data.getInt(TASK, -1) != task || task < 0 || data.getLong(GENERATION, -1) < 0) return false;
            Bundle envelope = new Bundle(data);
            envelope.putInt("mio.resultType", 0);
            return UiAgentDomProtocol.validRequest(envelope, actual);
        } catch (Throwable ignored) { return false; }
    }
    public static Result read(UiAgentDomProtocol.Target target, int task, long generation,
            boolean ignoreVisibility, BooleanSupplier current) {
        return read(target, task, generation, ignoreVisibility, current, null);
    }
    public static Result read(UiAgentDomProtocol.Target target, int task, long generation,
            boolean ignoreVisibility, BooleanSupplier current, UiAgentPageSignal signal) {
        try {
            var request = request(target, task, generation, ignoreVisibility);
            if (signal != null) signal.attach(request.bundle());
            var reply = UiAgentDomAccess.exchange(request, current, BACKEND);
            if (!reply.ok()) return failed(reply.status());
            if (!current.getAsBoolean() || !valid(request.bundle(), target, task)) return failed("STALE");
            return decode(reply.raw(), task, generation);
        } catch (Throwable ignored) { return failed("INVALID_NATIVE_REPLY"); }
    }
    public static Result decode(String raw, int task, long generation) {
        try {
            if (raw == null || raw.length() > 250_000) return failed("LIMIT");
            JSONObject value = new JSONObject(raw);
            String text = value.getString("text");
            int visited = value.getInt("visited");
            long source = value.getLong("sourceGeneration");
            if (value.getInt("version") != 1 || value.getInt("task") != task
                    || value.getLong("generation") != generation || source < 0
                    || visited < 1 || visited > 6000 || text.isEmpty() || text.length() > 40_000)
                return failed("IDENTITY_OR_SIZE");
            return new Result("OK", text, visited, source);
        } catch (Throwable ignored) { return failed("INVALID_NATIVE_REPLY"); }
    }
    /** Main-thread entry from the system-authenticated UIAgent client. Exact
     * rule/Activity matching happens at the requester; source checks actual task,
     * component, UID, lifetime and its own current lifecycle generation again. */
    public static String capture(Activity activity, Bundle data, IBinder callback,
            BooleanSupplier active, LongSupplier generation) {
        UiAgentDomProtocol.Target target = null;
        String nonce = "", status = "FAILED", raw = "";
        try {
            target = new UiAgentDomProtocol.Target(activity.getPackageName(),
                    activity.getApplicationInfo().uid, activity.getClass().getName());
            nonce = data == null ? "" : data.getString(UiAgentDomProtocol.NONCE_KEY, "");
            if (!ColorOsCollectorScope.nativePackageAllowed(target.packageName())
                    || !active.getAsBoolean() || !valid(data, target, activity.getTaskId())) status = "REJECTED";
            else {
                long sourceGeneration = generation.getAsLong();
                var local = ColorOsNativeSnapshot.ticket(activity, sourceGeneration);
                if (local == null) status = "UNSUPPORTED_API";
                else {
                    // Retain original request age, not a fresh TTL upon receipt.
                    var ticket = new ColorOsNativeSnapshot.Ticket(local.component(), local.uid(), local.task(),
                            local.token(), sourceGeneration, data.getLong("mio.createdElapsed"), nonce);
                    var snapshot = ColorOsNativeSnapshot.capture(activity, ticket, generation.getAsLong());
                    status = snapshot.status();
                    if ("OK".equals(status)) {
                        var selected = ColorOsNativeExtractor.extractNodes(snapshot.roots(), data.getBoolean("mio.ignoreVisible"));
                        status = selected.status();
                        if ("OK".equals(status)) raw = new JSONObject().put("version", 1)
                                .put("task", ticket.task()).put("generation", data.getLong(GENERATION))
                                .put("sourceGeneration", sourceGeneration).put("visited", selected.visited())
                                .put("text", selected.text()).toString();
                    }
                    if (!active.getAsBoolean() || generation.getAsLong() != sourceGeneration
                            || !valid(data, target, activity.getTaskId())) { status = "STALE"; raw = ""; }
                }
            }
        } catch (Throwable ignored) { status = "FAILED"; raw = ""; }
        if (target != null) UiAgentDomProtocol.send(callback, target, nonce, status, raw, BACKEND);
        return status;
    }
    private static Result failed(String status) { return new Result(status, "", 0, -1); }
}
