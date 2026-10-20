package dev.splitledger.bankimport;

import dev.splitledger.common.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SparkasseCsvCamtParserTest {

    static final Path SAMPLES = Path.of("..", "samples");

    private final SparkasseCsvCamtParser parser = new SparkasseCsvCamtParser();

    static byte[] sample(String name) throws IOException {
        return Files.readAllBytes(SAMPLES.resolve(name));
    }

    @Test
    void readsTheSyntheticWindows1252Export() throws IOException {
        StatementParser.Result result = parser.parse(sample("sparkasse-csv-camt-2026-03-a.csv"));

        assertThat(result.unreadableRows()).isEmpty();
        assertThat(result.transactions()).hasSize(9);

        ParsedTransaction rewe = result.transactions().getFirst();
        assertThat(rewe.line()).isEqualTo(2);
        assertThat(rewe.accountIban()).isEqualTo("DE81000010000001234567");
        assertThat(rewe.bookingDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(rewe.amountCents()).isEqualTo(-3847);
        assertThat(rewe.counterpartyName()).isEqualTo("REWE Markt Bonn-Poppelsdorf");
        assertThat(rewe.counterpartyIban()).isNull();
        assertThat(rewe.endToEndReference()).as("NOTPROVIDED means no reference").isNull();
        assertThat(rewe.booked()).isTrue();

        assertThat(result.transactions()).extracting(ParsedTransaction::counterpartyName).contains("Bäckerei Müller");
        ParsedTransaction rent = result.transactions().getLast();
        assertThat(rent.amountCents()).isEqualTo(-124000);
        assertThat(rent.counterpartyIban()).isEqualTo("DE05000030000001000001");
        ParsedTransaction electricity = result.transactions().get(7);
        assertThat(electricity.endToEndReference()).isEqualTo("SWB-2026-03-4711");
        assertThat(electricity.purpose()).isEqualTo("Abschlag Strom März Vertragskonto 4711");
    }

    @Test
    void marksPendingRows() throws IOException {
        List<ParsedTransaction> rows = parser.parse(sample("sparkasse-csv-camt-2026-03-b.csv")).transactions();

        assertThat(rows.getFirst().booked()).isFalse();
        assertThat(rows.subList(1, rows.size())).allMatch(ParsedTransaction::booked);
    }

    @Test
    void findsColumnsByNameInAnyOrderAndWithUmlauts() {
        String csv = """
                "Betrag";"Währung";"Begünstigter/Zahlungspflichtiger";"Kontonummer/IBAN";"Verwendungszweck";"Buchungstag";"Auftragskonto"
                "-9,99";"EUR";"Kino Bonn";"";"Tickets";"01.04.2026";"DE81 0000 1000 0001 2345 67"
                """;

        ParsedTransaction t = parser.parse(csv.getBytes(StandardCharsets.UTF_8)).transactions().getFirst();

        assertThat(t.amountCents()).isEqualTo(-999);
        assertThat(t.counterpartyName()).isEqualTo("Kino Bonn");
        assertThat(t.accountIban()).isEqualTo("DE81000010000001234567");
        assertThat(t.booked()).as("no Info column: treated as booked").isTrue();
    }

    @Test
    void reportsUnreadableRowsWithTheirLineAndKeepsTheRest() {
        String csv = """
                "Auftragskonto";"Buchungstag";"Verwendungszweck";"Beguenstigter/Zahlungspflichtiger";"Kontonummer/IBAN";"Betrag";"Waehrung"
                "DE81000010000001234567";"01.03.26";"ok";"A";"";"-1,00";"EUR"
                "DE81000010000001234567";"32.03.26";"bad date";"B";"";"-1,00";"EUR"
                "DE81000010000001234567";"02.03.26";"bad amount";"C";"";"-1.00";"EUR"
                "DE81000010000001234567";"02.03.26";"too few fields"
                """;

        StatementParser.Result result = parser.parse(csv.getBytes(StandardCharsets.UTF_8));

        assertThat(result.transactions()).hasSize(1);
        assertThat(result.unreadableRows()).extracting(StatementParser.SkippedRow::line).containsExactly(3, 4, 5);
        assertThat(result.unreadableRows().get(0).reason()).contains("not a date");
        assertThat(result.unreadableRows().get(2).reason()).contains("expected 7 fields");
    }

    @Test
    void rejectsFilesThatAreNotSparkasseExports() {
        assertThatThrownBy(() -> parser.parse("Date,Amount,Payee\n2026-03-01,-5.00,Shop\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Not a Sparkasse CSV-CAMT export");
        assertThatThrownBy(() -> parser.parse(new byte[0])).isInstanceOf(BusinessRuleException.class);
    }
}
