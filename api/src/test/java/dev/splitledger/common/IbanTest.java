package dev.splitledger.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class IbanTest {

    // Published example IBANs (no real accounts behind them).
    @ParameterizedTest
    @ValueSource(strings = {"DE89370400440532013000", "GB82WEST12345698765432", "de89 3704 0044 0532 0130 00"})
    void acceptsValidIbansWithOrWithoutSpaces(String iban) {
        assertThat(Iban.isValid(iban)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DE88370400440532013000", // check digits off by one
            "DE89370400440532013001", // last digit changed
            "DE89370400440523013000", // two digits swapped
            "DE89",                   // too short
            "1289370400440532013000", // country code must be letters
            "DE89-3704-0044-0532-0130-00"
    })
    void rejectsInvalidIbans(String iban) {
        assertThat(Iban.isValid(iban)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(Iban.isValid(null)).isFalse();
    }

    @Test
    void normalizesToUpperCaseWithoutSpaces() {
        assertThat(Iban.normalize(" de89 3704 0044 0532 0130 00 ")).isEqualTo("DE89370400440532013000");
    }
}
