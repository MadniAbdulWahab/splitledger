package dev.splitledger.common;

/**
 * Joins values into one unambiguous string for hashing: every value is written as
 * "length:value|", null as "-1:|". Without the length prefix, "ab" + "c" and "a" + "bc" would give
 * the same text and therefore the same hash.
 */
public final class CanonicalText {

    private CanonicalText() {
    }

    public static String of(Object... values) {
        StringBuilder out = new StringBuilder();
        for (Object value : values) {
            if (value == null) {
                out.append("-1:|");
            } else {
                String text = value.toString();
                out.append(text.length()).append(':').append(text).append('|');
            }
        }
        return out.toString();
    }
}
