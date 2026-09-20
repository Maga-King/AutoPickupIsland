package io.github.mio.autopickupisland;

/** Validates an already parsed, trusted AICR result; never extracts codes from page text. */
final class NativePickupCode {
    static String accept(String value, String tag) {
        if (value == null) return "";
        String code = value.trim();
        if (code.matches("[A-Za-z0-9]{3,10}")) return code.toUpperCase(java.util.Locale.ROOT);
        // OEM processStringStarbucks -> verifyStarbucks accepts numeric-dot text.
        // Scope this bridge extension to that rule, retain the complete phrase,
        // and bound printable characters. No stripping punctuation into a new code.
        if ("starbucks".equals(tag) && code.length() <= 32
                && code.matches("[0-9]{1,6}\\.[\\p{IsHan}A-Za-z0-9·（）() _-]{1,25}")) return code;
        return "";
    }
    private NativePickupCode() { }
}
