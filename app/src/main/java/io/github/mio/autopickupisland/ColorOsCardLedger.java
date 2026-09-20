package io.github.mio.autopickupisland;

import java.time.*;
import java.util.*;
import static io.github.mio.autopickupisland.ColorOsLifecycleRules.*;

/**
 * State-table portion of ColorOS o6.r: a1/e1/q1/I/H0/V/A0/E0.
 * Input must already have passed original page/status/consent/distinct-policy gates.
 * Does not infer order state, read a screen, post a notification or implement observers.
 *
 * Cross-system boundary: plan/commit keeps an old Xiaomi notification until replacement
 * is acknowledged. Original Seedling ends it before send; this transactional change is
 * explicit, so a failed Xiaomi submission cannot erase the user's existing pickup code.
 */
final class ColorOsCardLedger {
    record Candidate(Identity identity, String appName, Order order,
                     String product, String detail, long seenAt) { }
    record Card(Candidate data, String instanceId, long identityCreatedAt) { }
    enum Action { CREATE, UPDATE, REPLACE, KEEP_UNKNOWN, CLOSED_TODAY,
        OLD_RESULT, DUPLICATE_BRAND, END_FOR_DUPLICATE, INVALID, CAPACITY }
    record Plan(long revision, Action action, Candidate incoming, Card showing) { }
    private static final class ClosedItem {
        String code;
        int state;
        // Original g6.g constructor initializes e/f to MAX, not zero. E0 tests
        // the user-close timestamp even for timeout records during daily cleanup.
        long userClosedAt = Long.MAX_VALUE, timeoutClosedAt = Long.MAX_VALUE, seenAt;
        Closed asRule() { return new Closed(code, state, userClosedAt, timeoutClosedAt); }
    }
    private static final class ClosedGroup {
        final long identityCreatedAt;
        final List<ClosedItem> items = new ArrayList<>();
        ClosedGroup(long createdAt) { identityCreatedAt = createdAt; }
    }
    private final Map<Identity, Card> cards = new LinkedHashMap<>();
    private final Map<Identity, ClosedGroup> closed = new LinkedHashMap<>();
    private final Map<Identity, Map<String, Long>> firstSent = new LinkedHashMap<>();
    private final String coveredAppName;
    private final int maxIdentities;
    private long revision;
    private long nextCleanupReference;

    ColorOsCardLedger(long createdAt, String coveredAppName, int maxIdentities) {
        this.nextCleanupReference = createdAt; // Original o6.r.f12149c initialization.
        this.coveredAppName = Objects.requireNonNull(coveredAppName);
        if (maxIdentities < 1) throw new IllegalArgumentException("capacity");
        this.maxIdentities = maxIdentities; // Adapter memory safeguard, not a ColorOS TTL.
    }

    synchronized Plan plan(Candidate incoming, long now, ZoneId zone) {
        if (!valid(incoming)) return new Plan(revision, Action.INVALID, incoming, null);
        Card showing = cards.get(incoming.identity);
        ClosedGroup history = closed.get(incoming.identity);
        if (history != null) for (ClosedItem item : history.items)
            if (closedToday(incoming.order, item.asRule(), now, zone))
                return new Plan(revision, Action.CLOSED_TODAY, incoming, showing);
        if (showing != null) {
            if (rejectBySourceOrSequence(showing.data.order, incoming.order))
                return new Plan(revision, Action.OLD_RESULT, incoming, showing);
            if (Objects.equals(incoming.order.code(), showing.data.order.code())
                    && !differentAppName(incoming.appName, showing.data.appName))
                return new Plan(revision, Action.UPDATE, incoming, showing);
            if (preserveShowingForUnknown(true, incoming.order))
                return new Plan(revision, Action.KEEP_UNKNOWN, incoming, showing);
        }
        // Original a1 removes the old same-identity card before u0's other-brand check.
        for (Card other : cards.values()) {
            if (other == showing) continue;
            if (Objects.equals(other.data.appName, incoming.appName)
                    && Objects.equals(other.data.order.code(), incoming.order.code()))
                return new Plan(revision, showing == null ? Action.DUPLICATE_BRAND
                        : Action.END_FOR_DUPLICATE, incoming, showing);
        }
        if (showing == null && cards.size() >= maxIdentities)
            return new Plan(revision, Action.CAPACITY, incoming, null);
        return new Plan(revision, showing == null ? Action.CREATE : Action.REPLACE, incoming, showing);
    }

    /** Call only once the host confirms the intended create/replace/update/end operation. */
    synchronized boolean commit(Plan plan, String confirmedInstanceId, long now) {
        if (plan == null || plan.revision != revision || !valid(plan.incoming)) return false;
        if (cards.get(plan.incoming.identity) != plan.showing) return false;
        switch (plan.action) {
            case CREATE, REPLACE -> {
                if (confirmedInstanceId == null || confirmedInstanceId.isEmpty()) return false;
                for (Card card : cards.values())
                    if (confirmedInstanceId.equals(card.instanceId)) return false;
                if (!firstSent.containsKey(plan.incoming.identity) && firstSent.size() >= maxIdentities)
                    return false;
                cards.put(plan.incoming.identity, new Card(plan.incoming, confirmedInstanceId, now));
                firstSent.computeIfAbsent(plan.incoming.identity, key -> new LinkedHashMap<>())
                        .putIfAbsent(plan.incoming.order.code(), now); // o6.r.h0/S0, including unknown.
            }
            case UPDATE -> {
                if (plan.showing == null || !plan.showing.instanceId.equals(confirmedInstanceId)) return false;
                Candidate merged = mergeSameCode(plan.showing.data, plan.incoming);
                cards.put(plan.incoming.identity, new Card(merged, plan.showing.instanceId,
                        plan.showing.identityCreatedAt));
            }
            case END_FOR_DUPLICATE -> {
                if (plan.showing == null || !plan.showing.instanceId.equals(confirmedInstanceId)) return false;
                cards.remove(plan.incoming.identity);
            }
            default -> { return false; }
        }
        revision++;
        return true;
    }

    /** o6.r.q1: LOCAL updates do not invent or replace meal names. */
    private static Candidate mergeSameCode(Candidate showing, Candidate incoming) {
        String product = showing.product, detail = showing.detail;
        Order order = showing.order;
        Source source = incoming.order.source();
        if ((order.source() != Source.BREENO || source == Source.BREENO)
                && (source == Source.CLOUD || source == Source.BREENO)) {
            boolean changed = false;
            if ((empty(product) || source == Source.BREENO) && !empty(incoming.product)) {
                product = incoming.product;
                changed = true;
            }
            if (!empty(incoming.detail) && incoming.order.sequence() > order.sequence()) {
                detail = incoming.detail;
                changed = true;
            }
            if (changed) order = new Order(order.code(), order.state(), source,
                    incoming.order.sequence(), order.eventType());
        }
        return new Candidate(showing.identity, showing.appName, order, product, detail, incoming.seenAt);
    }

    /** Host has confirmed cancellation of this exact owned instance. Never close by code. */
    synchronized boolean closedByHost(String instanceId, int reason, int effectiveState, long now) {
        Card found = null;
        for (Card card : cards.values()) if (card.instanceId.equals(instanceId)) { found = card; break; }
        if (found == null) return false;
        if ((reason != 3 && reason != 4) || effectiveState < 3 || effectiveState > 7) return false;
        if (recordableCode(found.data.order.code())) {
            if (!closed.containsKey(found.data.identity) && closed.size() >= maxIdentities) return false;
            ClosedGroup group = closed.computeIfAbsent(found.data.identity, key -> new ClosedGroup(now));
            ClosedItem item = new ClosedItem();
            item.code = found.data.order.code();
            item.state = effectiveState;
            item.seenAt = found.data.seenAt;
            if (reason == 4) item.userClosedAt = now;
            if (reason == 3) item.timeoutClosedAt = now;
            boolean add = true;
            for (ClosedItem previous : group.items) if (Objects.equals(previous.code, item.code)) {
                // Original I compares prior state to requested reason, not effective mode state.
                if (previous.state >= reason) { add = false; break; }
                previous.state = reason;
                previous.seenAt = item.seenAt;
                if (reason == 4) previous.userClosedAt = now;
                if (reason == 3) previous.timeoutClosedAt = now;
                break;
            }
            // g6.g overrides equals(code) but not hashCode; original HashSet can retain
            // the new instance after updating the previous one. Keep both records here.
            if (add) group.items.add(item);
        }
        cards.remove(found.data.identity);
        revision++;
        return true;
    }

    /** o6.r.V/A0/E0/C0. Caller supplies an event; no polling/alarm is created. */
    synchronized List<String> cleanup(long now, ZoneId zone) {
        if (now - nextCleanupReference < 14_400_000L) return List.of();
        LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone);
        if (local.isBefore(local.withHour(4).withMinute(0).withSecond(0))) return List.of();
        // Intentionally retain original subsecond component and fixed +86400000 update.
        long midnight = local.withHour(0).withMinute(0).withSecond(0).atZone(zone).toInstant().toEpochMilli();
        nextCleanupReference = midnight + 86_400_000L;
        List<String> endInstances = new ArrayList<>();
        Iterator<Card> iterator = cards.values().iterator();
        while (iterator.hasNext()) {
            Card card = iterator.next();
            if (card.data.seenAt < midnight) {
                if (card.identityCreatedAt < midnight) endInstances.add(card.instanceId);
                iterator.remove();
            }
        }
        closed.entrySet().removeIf(entry -> entry.getValue().identityCreatedAt < midnight
                || entry.getValue().items.stream().anyMatch(item -> item.userClosedAt < midnight || item.seenAt < midnight));
        firstSent.entrySet().removeIf(entry -> {
            entry.getValue().entrySet().removeIf(item -> item.getValue() < midnight);
            return entry.getValue().isEmpty();
        });
        revision++;
        return List.copyOf(endInstances);
    }

    synchronized Card current(Identity identity) { return cards.get(identity); }
    synchronized int closedCount(Identity identity) {
        ClosedGroup group = closed.get(identity);
        return group == null ? 0 : group.items.size();
    }
    synchronized long firstSentAt(Identity identity, String code) {
        return firstSent.getOrDefault(identity, Map.of()).getOrDefault(code, 0L);
    }

    private boolean differentAppName(String first, String second) {
        return !Objects.equals(first, second) && !Objects.equals(first, coveredAppName)
                && !Objects.equals(second, coveredAppName);
    }
    private static boolean valid(Candidate candidate) {
        return candidate != null && candidate.identity != null && !empty(candidate.identity.identifier())
                && candidate.identity.userId() >= 0 && candidate.order != null
                && !empty(candidate.order.code()) && candidate.order.source() != null
                && candidate.appName != null;
    }
    private static boolean empty(String value) { return value == null || value.isEmpty(); }
}
