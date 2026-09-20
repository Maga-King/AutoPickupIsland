package io.github.mio.autopickupisland;

import android.content.Context;
import io.github.mio.autopickupisland.coloros.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;

/** OS4 transport around OEM two-stage reads. No OCR, heuristically supplied route,
 * content logging, network, notification or arbitrary JS. Caller supplies a task
 * snapshot and rechecks it before/after every cross-process transaction.
 */
final class OriginalDomPickup {
    record Outcome(String status, PickupEvent event, String timing) {
        Outcome(String status, PickupEvent event) { this(status, event, ""); }
    }
    static Outcome read(Context context, UiAgentDomProtocol.Target target, String taskLabel,
                        RuleRepository repository, BooleanSupplier current) {
        return read(context, target, taskLabel, repository, current, null);
    }
    static Outcome read(Context context, UiAgentDomProtocol.Target target, String taskLabel,
                        RuleRepository repository, BooleanSupplier current, UiAgentPageSignal signal) {
        StringBuilder timing = new StringBuilder();
        long began = android.os.SystemClock.elapsedRealtime();
        Outcome result = perform(context, target, taskLabel, repository, current, signal, timing);
        return new Outcome(result.status(), result.event(), "total="
                + (android.os.SystemClock.elapsedRealtime() - began) + "ms " + timing);
    }
    private static Outcome perform(Context context, UiAgentDomProtocol.Target target, String taskLabel,
                        RuleRepository repository, BooleanSupplier current, UiAgentPageSignal signal, StringBuilder timing) {
        try {
            if (!ColorOsCollectorScope.activityAllowed(target.packageName(), target.activity())
                    || !repository.hasPageScope(target.packageName(), taskLabel)) return new Outcome("OUTSIDE_SCOPE", null);
            var policy = repository.recognitionPolicy();
            long version = context.getPackageManager().getPackageInfo(target.packageName(), 0).getLongVersionCode();
            var metadata = UiAgentDomAccess.read(target, ColorOsWebViewClient.Request.pageId(),
                    policy.webSelection(target.packageName(), version, "pagechange", 3), current);
            timing.append("route:").append(metadata.status()).append('[').append(metadata.timing()).append("] ");
            if (!metadata.ok()) return new Outcome(metadata.status(), null);
            JSONArray row = new JSONArray(metadata.raw());
            if (row.length() < 4 || row.isNull(1) || row.isNull(2)) return new Outcome("NO_PAGE_ID", null);
            String route = row.getString(1), actualAppletId = row.getString(2);
            if (route.isEmpty() || !actualAppletId.matches("wx[A-Za-z0-9]{8,40}")) return new Outcome("NO_PAGE_ID", null);
            Rule rule = repository.matchPage(target.packageName(), taskLabel, "");
            if (rule == null || rule.pathTarget(route) == null) return new Outcome("OUTSIDE_ORDER_PAGE", null);
            // The exact-route branch only. Label-change consent/fuzzy expansion
            // is not silently enabled as a workaround for missing route evidence.
            List<String> paths = rule.filterPaths.stream().map(item -> item.value).toList();
            var body = UiAgentDomAccess.read(target,
                    ColorOsWebViewClient.Request.content(paths, false, false, rule.extractRootPortal),
                    policy.webSelection(target.packageName(), version, "pagechange", 4), current, signal);
            timing.append("body:").append(body.status()).append('[').append(body.timing()).append(']');
            if (!body.ok()) return new Outcome(body.status(), null);
            var parsed = ColorOsWebTextParser.parse(body.raw(), route, new ColorOsWebTextParser.PathPolicy() {
                public List<String> filterPaths() { return paths; }
                public boolean useCloud(String path) { return rule.useCloud(path); }
            });
            if (parsed.malformed() || parsed.limitExceeded()) return new Outcome("INVALID_DOM", null);
            var state = parsed.state();
            if (!route.equals(state.route) || !actualAppletId.equals(state.wechatAppId) || !current.getAsBoolean())
                return new Outcome("PAGE_CHANGED", null);
            String text = String.join("\n", state.texts()); // Preserve OEM order and duplicate text.
            if (text.isEmpty() || text.length() > Constants.MAX_CONTENT_LENGTH) return new Outcome("EMPTY_OR_LIMIT", null);
            PickupEvent event = rule.event(target.activity(), rule.originId, state.route, state.query,
                    state.href, text, rule.pathTarget(state.route), repository.version());
            event.actualTaskLabel = taskLabel;
            event.actualAppletId = actualAppletId; // wx identifier, not OpenSDK's gh original identifier.
            event.userId = target.uid() / 100_000;
            event.eventSource = "pagechange";
            event.sequence = android.os.SystemClock.elapsedRealtimeNanos();
            return new Outcome("OK", event);
        } catch (Throwable ignored) { return new Outcome("FAILED", null); }
    }
    private OriginalDomPickup() { }
}
