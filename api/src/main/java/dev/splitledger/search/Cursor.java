package dev.splitledger.search;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Position after the last result of a page: its score and id. The next page continues with rows
 * that sort strictly after it. Encoded as opaque base64 so clients do not build cursors themselves.
 * Double.toString/parseDouble round-trip exactly, so no row is skipped or repeated at the boundary.
 */
record Cursor(double score, long id) {

    String encode() {
        String raw = score + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int colon = raw.lastIndexOf(':');
            return new Cursor(Double.parseDouble(raw.substring(0, colon)), Long.parseLong(raw.substring(colon + 1)));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Invalid cursor", e);
        }
    }
}
