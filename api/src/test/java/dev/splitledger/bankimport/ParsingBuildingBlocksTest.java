package dev.splitledger.bankimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Amounts, dates, encodings and the CSV reader, each tested in isolation. */
class ParsingBuildingBlocksTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "-12,50     | -1250",
            "12,50      | 1250",
            "+3,20      | 320",
            "-1.240,00  | -124000",
            "1.234.567,89 | 123456789",
            "-0,01      | -1",
            "7          | 700",
            "7,5        | 750",
            "1240,00    | 124000"
    })
    void parsesGermanAmountsIntoCents(String text, long cents) {
        assertThat(GermanFormats.parseCents(text)).isEqualTo(cents);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12.50", "1,234", "-12,505", "12,", "abc", "1.24,00", ""})
    void rejectsAmountsThatAreNotGermanFormat(String text) {
        assertThatThrownBy(() -> GermanFormats.parseCents(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesShortAndLongDates() {
        assertThat(GermanFormats.parseDate("15.03.26")).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(GermanFormats.parseDate("01.12.2025")).isEqualTo(LocalDate.of(2025, 12, 1));
        assertThatThrownBy(() -> GermanFormats.parseDate("31.02.26")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GermanFormats.parseDate("2026-03-15")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodesWindows1252AndUtf8() {
        String text = "Bäckerei Müller €";
        assertThat(TextDecoding.decode(text.getBytes(TextDecoding.WINDOWS_1252))).isEqualTo(text);
        assertThat(TextDecoding.decode(text.getBytes(StandardCharsets.UTF_8))).isEqualTo(text);

        byte[] withBom = ("﻿" + text).getBytes(StandardCharsets.UTF_8);
        assertThat(TextDecoding.decode(withBom)).isEqualTo(text);
    }

    @Test
    void readsQuotedFieldsWithSeparatorsQuotesAndLineBreaks() {
        String csv = "\"a\";\"b;c\";\"say \"\"hi\"\"\"\r\n"
                + "\"multi\nline\";plain;\"\"\r\n"
                + "\r\n"
                + "last;row;x";

        List<DelimitedText.Row> rows = DelimitedText.parse(csv, ';');

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).fields()).containsExactly("a", "b;c", "say \"hi\"");
        assertThat(rows.get(1).fields()).containsExactly("multi\nline", "plain", "");
        assertThat(rows.get(2).line()).isEqualTo(5);
        assertThat(rows.get(2).fields()).containsExactly("last", "row", "x");
    }
}
