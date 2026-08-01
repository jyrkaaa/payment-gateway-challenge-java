package com.checkout.payment.gateway.validation;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class ExpiryDateValidatorTest {

    private ExpiryDateValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ExpiryDateValidator();
    }

    private PostPaymentRequest request(int month, int year) {
        PostPaymentRequest r = new PostPaymentRequest();
        r.setExpiryMonth(month);
        r.setExpiryYear(year);
        return r;
    }

    @Test
    void pastDate_invalid() {
        assertThat(validator.isValid(request(1, 2020), null)).isFalse();
    }

    @Test
    void currentMonthYear_invalid() {
        YearMonth now = YearMonth.now();
        assertThat(validator.isValid(request(now.getMonthValue(), now.getYear()), null)).isFalse();
    }

    @Test
    void nextMonth_valid() {
        YearMonth next = YearMonth.now().plusMonths(1);
        assertThat(validator.isValid(request(next.getMonthValue(), next.getYear()), null)).isTrue();
    }

    @Test
    void futureYear_valid() {
        assertThat(validator.isValid(request(4, 2030), null)).isTrue();
    }

    @Test
    void nullRequest_valid() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void invalidMonth_skipsCheck() {
        assertThat(validator.isValid(request(0, 2030), null)).isTrue();
    }
}
