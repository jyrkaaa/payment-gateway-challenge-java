package com.checkout.payment.gateway.validation;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRequestValidatorTest {

    private PaymentRequestValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PaymentRequestValidator();
    }

    @Test
    void unsupportedCurrency_returnsError() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCurrency("CAD");
        assertThat(validator.isValid(request, null)).isFalse();
    }

    @Test
    void nullCurrency_noError() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCurrency(null);
        assertThat(validator.isValid(request, null)).isTrue();
    }

    @Test
    void shortCurrency_returnsError() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCurrency("US");
        assertThat(validator.isValid(request, null)).isFalse();
    }

    @Test
    void usd_noError() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCurrency("USD");
        assertThat(validator.isValid(request, null)).isTrue();
    }

    @Test
    void gbp_noError() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCurrency("GBP");
        assertThat(validator.isValid(request, null)).isTrue();
    }

    @Test
    void eur_noError() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCurrency("EUR");
        assertThat(validator.isValid(request, null)).isTrue();
    }

    @Test
    void jpy_returnsError() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCurrency("JPY");
        assertThat(validator.isValid(request, null)).isFalse();
    }

    @Test
    void eurWithLower_returnsError() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCurrency("eur");
        assertThat(validator.isValid(request, null)).isFalse();
    }
}
