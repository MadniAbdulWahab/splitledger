package dev.splitledger.bankimport;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintsTest {

    private static ParsedTransaction coffee(int line) {
        return new ParsedTransaction(line, "DE81000010000001234567", LocalDate.of(2026, 3, 12), -320, "EUR",
                "Cafe Mokka", null, "Cafe Mokka Bonn/Kartenzahlung girocard", null, null, true);
    }

    @Test
    void twoIdenticalCoffeesOnOneDayGetDifferentFingerprints() {
        List<String> fingerprints = Fingerprints.of(List.of(coffee(3), coffee(4)));

        assertThat(fingerprints.get(0)).isNotEqualTo(fingerprints.get(1));
    }

    @Test
    void theSameRowsInAnotherExportGetTheSameFingerprints() {
        ParsedTransaction rent = new ParsedTransaction(9, "DE81000010000001234567", LocalDate.of(2026, 3, 1), -124000,
                "EUR", "Hausverwaltung", "DE05000030000001000001", "Miete März", null, null, true);

        List<String> firstExport = Fingerprints.of(List.of(coffee(3), coffee(4), rent));
        // Other export: different line numbers and another row in between; the two coffees are
        // still coffee #0 and #1 of that day.
        List<String> secondExport = Fingerprints.of(List.of(rent, coffee(20), coffee(21)));

        assertThat(secondExport).containsExactlyInAnyOrderElementsOf(firstExport);
    }

    @Test
    void ignoresDifferencesInWhitespaceOnly() {
        ParsedTransaction padded = new ParsedTransaction(3, "DE81000010000001234567", LocalDate.of(2026, 3, 12), -320,
                "EUR", "  Cafe Mokka ", null, "Cafe Mokka  Bonn/Kartenzahlung\ngirocard", null, null, true);

        assertThat(Fingerprints.of(List.of(padded))).isEqualTo(Fingerprints.of(List.of(coffee(3))));
    }

    @Test
    void anyDifferenceInTheFieldsGivesAnotherFingerprint() {
        String base = Fingerprints.of(List.of(coffee(3))).getFirst();
        ParsedTransaction otherDay = new ParsedTransaction(3, "DE81000010000001234567", LocalDate.of(2026, 3, 13), -320,
                "EUR", "Cafe Mokka", null, "Cafe Mokka Bonn/Kartenzahlung girocard", null, null, true);
        ParsedTransaction otherAmount = new ParsedTransaction(3, "DE81000010000001234567", LocalDate.of(2026, 3, 12), -330,
                "EUR", "Cafe Mokka", null, "Cafe Mokka Bonn/Kartenzahlung girocard", null, null, true);
        ParsedTransaction otherAccount = new ParsedTransaction(3, "DE89370400440532013000", LocalDate.of(2026, 3, 12), -320,
                "EUR", "Cafe Mokka", null, "Cafe Mokka Bonn/Kartenzahlung girocard", null, null, true);

        assertThat(Fingerprints.of(List.of(otherDay)).getFirst()).isNotEqualTo(base);
        assertThat(Fingerprints.of(List.of(otherAmount)).getFirst()).isNotEqualTo(base);
        assertThat(Fingerprints.of(List.of(otherAccount)).getFirst()).isNotEqualTo(base);
    }

    @Test
    void bankReferenceAloneIdentifiesTheTransaction() {
        ParsedTransaction a = new ParsedTransaction(1, "DE81000010000001234567", LocalDate.of(2026, 3, 12), -320,
                "EUR", "Cafe Mokka", null, "purpose as in CSV", null, "2026031200123", true);
        ParsedTransaction b = new ParsedTransaction(7, "DE81000010000001234567", LocalDate.of(2026, 3, 12), -320,
                "EUR", "CAFE MOKKA", null, "purpose formatted differently", null, "2026031200123", true);

        assertThat(Fingerprints.of(List.of(a))).isEqualTo(Fingerprints.of(List.of(b)));
    }
}
