package io.github.mio.autopickupisland;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Pure branches transcribed from ColorOS 16 o6.r / i6.g / n6.a, with source references.
 * Not yet the complete Seedling manager: scheduling, persistence and observer side-effects
 * must be connected separately. No Android hooks, notifications, timers or guessed TTLs.
 */
final class ColorOsLifecycleRules {
    static final String UNKNOWN_CODE = "$t('strings.card_pickup_code_unknown')";
    static final String MANUAL_EVENT = "BreenoMemory";
    // Original g6.g$a ordinals, not subjective confidence rankings.
    enum Source { DEFAULT, LOCAL, CLOUD, BREENO, OBSERVER }
    record Identity(String identifier, int userId) { // n6.a.equals excludes time/flag.
        @Override public int hashCode() { return Objects.hash(identifier, userId); }
    }
    record Order(String code, int state, Source source, long sequence, String eventType) { }
    record Closed(String code, int state, long userClosedAt, long timeoutClosedAt) { }

    /** i6.g and m6.k.r: package_name, or actual mini-program task label + host package. */
    static Identity identity(boolean webView, String actualLabel, String hostPackage,
                             String configuredPackage, int userId) {
        if (webView && actualLabel != null && !actualLabel.isEmpty() && !"null".equals(actualLabel)
                && ("com.tencent.mm".equals(hostPackage) || "com.eg.android.AlipayGphone".equals(hostPackage)))
            return new Identity(actualLabel + hostPackage, userId);
        String identifier = configuredPackage == null || configuredPackage.isEmpty()
                ? hostPackage : configuredPackage;
        return new Identity(identifier, userId);
    }

    /** o6.r.H0. Manual event exemption uses event type, NOT algorithm source. */
    static boolean closedToday(Order incoming, Closed previous, long now, ZoneId zone) {
        if (incoming == null || previous == null || previous.code == null
                || !previous.code.equals(incoming.code)) return false;
        if (previous.state == 4 && !MANUAL_EVENT.equals(incoming.eventType)
                && sameDay(now, previous.userClosedAt, zone)) return true;
        return previous.state == 3 && incoming.state != 1
                && sameDay(now, previous.timeoutClosedAt, zone);
    }

    /** o6.r.e1 decision only. Its observer-restart side effect belongs to the caller. */
    static boolean rejectBySourceOrSequence(Order showing, Order incoming) {
        if (showing == null || incoming == null || showing.source == null || incoming.source == null)
            return false;
        if (!Objects.equals(incoming.code, showing.code))
            return incoming.source != Source.BREENO && incoming.sequence < showing.sequence;
        return showing.source.ordinal() >= Source.BREENO.ordinal()
                && incoming.source.ordinal() <= Source.CLOUD.ordinal();
    }

    /** o6.r.a1: an unknown result must not end an already existing matching card. */
    static boolean preserveShowingForUnknown(boolean hasMatchingCard, Order incoming) {
        return hasMatchingCard && incoming != null && UNKNOWN_CODE.equals(incoming.code);
    }

    /** o6.r.I / f1: unknown is neither a closed-code record nor an observed actual code. */
    static boolean recordableCode(String code) {
        return code != null && !code.isEmpty() && !UNKNOWN_CODE.equals(code);
    }

    private static boolean sameDay(long a, long b, ZoneId zone) {
        return Instant.ofEpochMilli(a).atZone(zone).toLocalDate()
                .equals(Instant.ofEpochMilli(b).atZone(zone).toLocalDate());
    }

    private ColorOsLifecycleRules() { }
}
