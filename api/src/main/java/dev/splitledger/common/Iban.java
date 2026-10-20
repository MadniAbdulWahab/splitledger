package dev.splitledger.common;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * IBAN check according to ISO 13616: move the first four characters to the end, replace letters
 * by numbers (A=10 ... Z=35) and the resulting number mod 97 must be 1. This catches all single
 * typos and almost all swapped digits; it does not prove that the account exists.
 */
public final class Iban {

    private static final Pattern FORMAT = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");

    private Iban() {
    }

    /** Removes spaces and upper-cases, so "de89 3704 ..." and "DE893704..." are stored the same way. */
    public static String normalize(String raw) {
        return raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String raw) {
        if (raw == null) {
            return false;
        }
        String iban = normalize(raw);
        if (!FORMAT.matcher(iban).matches()) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        // Process digit by digit so the 30+ digit number never has to fit into a long.
        int remainder = 0;
        for (int i = 0; i < rearranged.length(); i++) {
            int value = Character.digit(rearranged.charAt(i), 36);
            remainder = value < 10
                    ? (remainder * 10 + value) % 97
                    : (remainder * 100 + value) % 97;
        }
        return remainder == 1;
    }
}
