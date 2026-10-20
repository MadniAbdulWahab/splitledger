package dev.splitledger.bankimport;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Amounts like "-1.240,00" and dates like "15.03.26" as German banks write them. */
final class GermanFormats {

    // Optional sign, euros with optional "." every three digits, optional ",c" or ",cc".
    private static final Pattern AMOUNT = Pattern.compile("([+-]?)(\\d{1,3}(?:\\.\\d{3})+|\\d+)(?:,(\\d{1,2}))?");

    // "uu" with STRICT resolves two-digit years to 2000-2099.
    private static final DateTimeFormatter SHORT_DATE =
            DateTimeFormatter.ofPattern("dd.MM.uu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter LONG_DATE =
            DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);

    private GermanFormats() {
    }

    /** Parses the text straight into cents; the amount never passes through a floating-point type. */
    static long parseCents(String text) {
        Matcher m = AMOUNT.matcher(text.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("not an amount: '" + text + "'");
        }
        long euros = Long.parseLong(m.group(2).replace(".", ""));
        String decimals = m.group(3) == null ? "00" : (m.group(3) + "0").substring(0, 2);
        long cents = Math.addExact(Math.multiplyExact(euros, 100), Long.parseLong(decimals));
        return "-".equals(m.group(1)) ? -cents : cents;
    }

    static LocalDate parseDate(String text) {
        String value = text.strip();
        try {
            return LocalDate.parse(value, value.length() == 8 ? SHORT_DATE : LONG_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("not a date: '" + text + "'");
        }
    }
}
