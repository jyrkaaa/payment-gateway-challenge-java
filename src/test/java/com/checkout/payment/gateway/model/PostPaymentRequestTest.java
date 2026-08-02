package com.checkout.payment.gateway.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostPaymentRequestTest {

    @Test
    void getExpiryDate_formatsMonthAndYear() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setExpiryMonth(4);
        request.setExpiryYear(2030);

        assertThat(request.getExpiryDate()).isEqualTo("04/2030");
    }

    @Test
    void getExpiryDate_padsSingleDigitMonth() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setExpiryMonth(1);
        request.setExpiryYear(2025);

        assertThat(request.getExpiryDate()).isEqualTo("01/2025");
    }

    @Test
    void getCardNumberLastFour_normalCardNumber_returnsLastFourDigits() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCardNumber("2222405343248877");

        assertThat(request.getCardNumberLastFour()).isEqualTo("8877");
    }

    @Test
    void getCardNumberLastFour_nullCardNumber_returnsNull() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCardNumber(null);

        assertThat(request.getCardNumberLastFour()).isNull();
    }

    @Test
    void getCardNumberLastFour_cardNumberShorterThanFour_returnsAsIs() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCardNumber("123");

        assertThat(request.getCardNumberLastFour()).isEqualTo("123");
    }

    @Test
    void getCardNumberLastFour_cardNumberExactlyFour_returnsFullNumber() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCardNumber("1234");

        assertThat(request.getCardNumberLastFour()).isEqualTo("1234");
    }

    @Test
    void toString_excludesCardNumberAndCvv() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCardNumber("2222405343248877");
        request.setCvv("123");
        request.setCurrency("GBP");

        assertThat(request.toString())
            .doesNotContain("2222405343248877")
            .doesNotContain("123")
            .contains("GBP");
    }
}
