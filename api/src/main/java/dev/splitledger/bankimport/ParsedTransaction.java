package dev.splitledger.bankimport;

import java.time.LocalDate;

/**
 * One statement line in a format-independent shape. Every parser produces these; everything after
 * parsing (filtering, fingerprints, duplicates) is shared by all formats.
 *
 * @param line               line number in the file, for error reports
 * @param bankReference      reference assigned by the bank (CAMT.053 AcctSvcrRef); null when the
 *                           format has none, like the Sparkasse CSV
 * @param endToEndReference  reference set by the payer; often missing or "NOTPROVIDED"
 * @param booked             false for pending ("vorgemerkt") transactions, which can still change
 */
public record ParsedTransaction(
        int line,
        String accountIban,
        LocalDate bookingDate,
        long amountCents,
        String currency,
        String counterpartyName,
        String counterpartyIban,
        String purpose,
        String endToEndReference,
        String bankReference,
        boolean booked) {
}
