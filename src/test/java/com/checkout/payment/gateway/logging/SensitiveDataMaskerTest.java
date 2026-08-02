package com.checkout.payment.gateway.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    @Test
    void nullInput_returnsNull() {
        assertThat(SensitiveDataMasker.mask(null)).isNull();
    }

    @Test
    void blankInput_returnsBlank() {
        assertThat(SensitiveDataMasker.mask("   ")).isEqualTo("   ");
    }

    @Test
    void unparsableJson_returnsPlaceholder() {
        assertThat(SensitiveDataMasker.mask("not-json")).isEqualTo("[unparseable]");
    }

    @Test
    void cvvField_isFullyRedacted() {
        String input = "{\"cvv\":\"123\"}";
        String result = SensitiveDataMasker.mask(input);
        assertThat(result).contains("\"cvv\":\"***\"");
        assertThat(result).doesNotContain("123");
    }

    @Test
    void cardNumberField_lastFourVisible() {
        String input = "{\"card_number\":\"2222405343248877\"}";
        String result = SensitiveDataMasker.mask(input);
        assertThat(result).contains("8877");
        assertThat(result).doesNotContain("2222405343248877");
        assertThat(result).contains("************8877");
    }

    @Test
    void cardNumberShorterThanFour_fullyMasked() {
        String input = "{\"card_number\":\"123\"}";
        String result = SensitiveDataMasker.mask(input);
        assertThat(result).contains("\"card_number\":\"***\"");
        assertThat(result).doesNotContain("123");
    }

    @Test
    void otherFields_areNotMasked() {
        String input = "{\"currency\":\"GBP\",\"amount\":100}";
        String result = SensitiveDataMasker.mask(input);
        assertThat(result).contains("GBP");
        assertThat(result).contains("100");
    }

    @Test
    void nestedCvv_isRedacted() {
        String input = "{\"payment\":{\"cvv\":\"456\"}}";
        String result = SensitiveDataMasker.mask(input);
        assertThat(result).contains("\"cvv\":\"***\"");
        assertThat(result).doesNotContain("456");
    }

    @Test
    void arrayWithCvv_isRedacted() {
        String input = "[{\"cvv\":\"789\"}]";
        String result = SensitiveDataMasker.mask(input);
        assertThat(result).contains("\"cvv\":\"***\"");
        assertThat(result).doesNotContain("789");
    }

    @Test
    void fullPaymentBody_masksCvvAndCardNumber() {
        String input = "{\"card_number\":\"2222405343248877\",\"cvv\":\"123\",\"currency\":\"GBP\",\"amount\":100}";
        String result = SensitiveDataMasker.mask(input);
        assertThat(result).contains("\"cvv\":\"***\"");
        assertThat(result).contains("8877");
        assertThat(result).contains("GBP");
        assertThat(result).doesNotContain("2222405343248877");
        assertThat(result).doesNotContain("\"123\"");
    }
}
