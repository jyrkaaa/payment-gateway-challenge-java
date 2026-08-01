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
        assertThat(validator.validate(request)).anyMatch(e -> e.contains("currency"));
    }

    @Test
    void nullCurrency_noError() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCurrency(null);
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void shortCurrency_noError() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCurrency("US");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void usd_noError() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCurrency("USD");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void gbp_noError() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCurrency("GBP");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void eur_noError() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCurrency("EUR");
        assertThat(validator.validate(request)).isEmpty();
    }
}
