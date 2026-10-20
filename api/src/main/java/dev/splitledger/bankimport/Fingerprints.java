package dev.splitledger.bankimport;

import dev.splitledger.common.CanonicalText;
import dev.splitledger.common.Sha256;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A fingerprint identifies a bank transaction across imports, so re-importing the same or an
 * overlapping statement adds nothing new.
 *
 * <ul>
 *   <li>With a reference assigned by the bank (CAMT.053 AcctSvcrRef): SHA-256 of account and that
 *       reference; it is unique per account by definition.</li>
 *   <li>Without one (Sparkasse CSV): SHA-256 of account, booking date, amount, counterparty IBAN and
 *       name, purpose text and end-to-end reference, plus an occurrence index.</li>
 * </ul>
 *
 * <p>The occurrence index is the trick for genuinely identical payments: two coffees for 3.20 EUR
 * at the same café on the same day produce identical rows. Without the index they would get the
 * same fingerprint and the second would be dropped as a "duplicate". With it, they become
 * coffee #0 and coffee #1 of that day. Another export covering the same day contains the same two
 * rows, numbers them the same way, and both are recognized. (Limit: an export that cuts a day in
 * half and contains only one of the two would be read as coffee #0. Bank exports cover whole days.)
 */
final class Fingerprints {

    private Fingerprints() {
    }

    /** Fingerprints in the same order as the given transactions, which must come from one file. */
    static List<String> of(List<ParsedTransaction> transactions) {
        Map<String, Integer> seen = new HashMap<>();
        List<String> fingerprints = new ArrayList<>(transactions.size());
        for (ParsedTransaction t : transactions) {
            String identity = identity(t);
            int occurrence = seen.merge(identity, 1, Integer::sum) - 1;
            fingerprints.add(Sha256.hex(identity + CanonicalText.of("occurrence", occurrence)));
        }
        return fingerprints;
    }

    private static String identity(ParsedTransaction t) {
        if (t.bankReference() != null) {
            return CanonicalText.of("bank-reference", t.accountIban(), t.bankReference());
        }
        return CanonicalText.of("fields", t.accountIban(), t.bookingDate(), t.amountCents(), t.counterpartyIban(),
                collapse(t.counterpartyName()), collapse(t.purpose()), t.endToEndReference());
    }

    /** Exports sometimes differ only in padding or line wrapping inside the purpose text. */
    private static String collapse(String text) {
        return text == null ? null : text.strip().replaceAll("\\s+", " ");
    }
}
