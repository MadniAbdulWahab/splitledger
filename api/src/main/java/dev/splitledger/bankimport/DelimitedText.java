package dev.splitledger.bankimport;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal CSV reader for bank exports: configurable separator, fields optionally in double
 * quotes, "" for a quote inside a quoted field, separators and line breaks allowed inside quotes,
 * CRLF or LF line endings. Small enough to read in one sitting, so no CSV library is needed.
 */
final class DelimitedText {

    record Row(int line, List<String> fields) {
    }

    private DelimitedText() {
    }

    static List<Row> parse(String text, char separator) {
        List<Row> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int line = 1;
        int rowStartLine = 1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    inQuotes = false;
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == separator) {
                fields.add(field.toString());
                field.setLength(0);
            } else if (c == '\r') {
                // Part of CRLF; the '\n' ends the row.
            } else if (c == '\n') {
                fields.add(field.toString());
                addIfNotBlank(rows, rowStartLine, fields);
                fields = new ArrayList<>();
                field.setLength(0);
                line++;
                rowStartLine = line;
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        addIfNotBlank(rows, rowStartLine, fields);
        return rows;
    }

    private static void addIfNotBlank(List<Row> rows, int line, List<String> fields) {
        boolean blank = fields.stream().allMatch(String::isBlank);
        if (!blank) {
            rows.add(new Row(line, List.copyOf(fields)));
        }
    }
}
