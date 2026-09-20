package io.github.mio.autopickupisland;

import java.time.Instant;
import java.time.ZoneId;

final class PickupDeduplication {
    // ColorOS k6/c.p(JJ): compare local dates in the current system timezone.
    static boolean suppress(String key, String previous, long now, long publishedAt) {
        long age = now - publishedAt;
        return key.equals(previous) && age >= 0 && age < 10 * 60_000L
                && Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
                .equals(Instant.ofEpochMilli(publishedAt).atZone(ZoneId.systemDefault()).toLocalDate());
    }
    private PickupDeduplication() { }
}
