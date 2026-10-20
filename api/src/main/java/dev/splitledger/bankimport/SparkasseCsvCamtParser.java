package dev.splitledger.bankimport;

import dev.splitledger.common.BusinessRuleException;
import dev.splitledger.common.Iban;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the Sparkasse online-banking export in the "CSV-CAMT" layout. Built against the documented
 * layout with synthetic sample files (see samples/README.md); a real export should be compared
 * locally before relying on it.
 *
 * <p>Columns are found by header name, not position, so a changed column order or an extra column
 * does not break the import. Header names are compared with umlauts spelled out ("Begünstigter"
 * and "Beguenstigter" are the same column).
 */
@Component
class SparkasseCsvCamtParser implements StatementParser {

    static final String ACCOUNT = "auftragskonto";
    static final String BOOKING_DATE = "buchungstag";
    static final String PURPOSE = "verwendungszweck";
    static final String END_TO_END = "kundenreferenz (end-to-end)";
    static final String COUNTERPARTY = "beguenstigter/zahlungspflichtiger";
    static final String COUNTERPARTY_IBAN = "kontonummer/iban";
    static final String AMOUNT = "betrag";
    static final String CURRENCY = "waehrung";
    static final String INFO = "info";

    private static final List<String> REQUIRED =
            List.of(ACCOUNT, BOOKING_DATE, PURPOSE, COUNTERPARTY, COUNTERPARTY_IBAN, AMOUNT, CURRENCY);

    @Override
    public StatementFormat format() {
        return StatementFormat.SPARKASSE_CSV_CAMT;
    }

    @Override
    public Result parse(byte[] content) {
        List<DelimitedText.Row> rows = DelimitedText.parse(TextDecoding.decode(content), ';');
        if (rows.isEmpty()) {
            throw new BusinessRuleException("The file is empty");
        }
        Map<String, Integer> columns = columnIndex(rows.getFirst().fields());
        List<String> missing = REQUIRED.stream().filter(name -> !columns.containsKey(name)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessRuleException("Not a Sparkasse CSV-CAMT export: missing columns " + missing);
        }

        List<ParsedTransaction> transactions = new ArrayList<>();
        List<SkippedRow> unreadable = new ArrayList<>();
        for (DelimitedText.Row row : rows.subList(1, rows.size())) {
            if (row.fields().size() != rows.getFirst().fields().size()) {
                unreadable.add(new SkippedRow(row.line(), "expected " + rows.getFirst().fields().size()
                        + " fields, found " + row.fields().size()));
                continue;
            }
            try {
                transactions.add(toTransaction(row, columns));
            } catch (IllegalArgumentException | ArithmeticException e) {
                unreadable.add(new SkippedRow(row.line(), e.getMessage()));
            }
        }
        return new Result(transactions, unreadable);
    }

    private static ParsedTransaction toTransaction(DelimitedText.Row row, Map<String, Integer> columns) {
        String info = value(row, columns, INFO);
        String endToEnd = value(row, columns, END_TO_END);
        String counterpartyIban = value(row, columns, COUNTERPARTY_IBAN);
        return new ParsedTransaction(
                row.line(),
                Iban.normalize(required(row, columns, ACCOUNT)),
                GermanFormats.parseDate(required(row, columns, BOOKING_DATE)),
                GermanFormats.parseCents(required(row, columns, AMOUNT)),
                required(row, columns, CURRENCY).toUpperCase(Locale.ROOT),
                value(row, columns, COUNTERPARTY),
                counterpartyIban == null ? null : Iban.normalize(counterpartyIban),
                value(row, columns, PURPOSE),
                endToEnd == null || endToEnd.equalsIgnoreCase("NOTPROVIDED") ? null : endToEnd,
                null, // the Sparkasse CSV has no reference assigned by the bank
                info == null || !info.toLowerCase(Locale.ROOT).contains("vorgemerkt"));
    }

    private static Map<String, Integer> columnIndex(List<String> header) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            index.putIfAbsent(normalizeHeader(header.get(i)), i);
        }
        return index;
    }

    static String normalizeHeader(String name) {
        return name.strip().toLowerCase(Locale.GERMAN)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    /** The column's value, trimmed; null if the column is absent or the value is empty. */
    private static String value(DelimitedText.Row row, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        if (index == null) {
            return null;
        }
        String value = row.fields().get(index).strip();
        return value.isEmpty() ? null : value;
    }

    private static String required(DelimitedText.Row row, Map<String, Integer> columns, String column) {
        String value = value(row, columns, column);
        if (value == null) {
            throw new IllegalArgumentException("empty " + column);
        }
        return value;
    }
}
