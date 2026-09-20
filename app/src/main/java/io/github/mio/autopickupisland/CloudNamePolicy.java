package io.github.mio.autopickupisland;

import java.util.List;

/** Display-only cloud merge boundaries; no Android services, clocks or network. */
final class CloudNamePolicy {
    static final long WINDOW_MS = 30_000L;
    static boolean current(boolean enabled, boolean cancelled, long expectedRevision, long revision,
            long now, long deadline, String expectedInstance, String instance, String expectedCode, String code) {
        return enabled && !cancelled && expectedRevision >= 0 && expectedRevision == revision
                && deadline > now && deadline - now <= WINDOW_MS
                && expectedInstance != null && expectedInstance.matches("[a-f0-9-]{36}")
                && expectedInstance.equals(instance) && expectedCode != null && !expectedCode.isEmpty()
                && expectedCode.equals(code);
    }
    static String product(String expectedCode, List<String> codes, String state, String name) {
        if (expectedCode == null || expectedCode.isEmpty() || codes == null || codes.size() != 1
                || !expectedCode.equals(codes.get(0)) || !"uncompleted".equals(state) || name == null) return "";
        String value = name.strip();
        if (value.isEmpty() || value.length() > 48 || value.equalsIgnoreCase("null")
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) return "";
        return value;
    }
    private CloudNamePolicy() { }
}
