package com.checkout.payment.gateway.mappers;

import com.checkout.payment.gateway.client.BankPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BankPaymentMapperTest {

    @Test
    void from_mapsAllFields() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCardNumber("2222405343248877");
        request.setExpiryMonth(4);
        request.setExpiryYear(2030);
        request.setCurrency("GBP");
        request.setAmount(BigDecimal.valueOf(10000));
        request.setCvv("123");

        BankPaymentRequest result = BankPaymentMapper.from(request);

        assertThat(result.getCardNumber()).isEqualTo("2222405343248877");
        assertThat(result.getExpiryDate()).isEqualTo("04/2030");
        assertThat(result.getCurrency()).isEqualTo("GBP");
        assertThat(result.getAmount()).isEqualTo(10000);
        assertThat(result.getCvv()).isEqualTo("123");
    }

    @Test
    void from_singleDigitMonth_isZeroPadded() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCardNumber("12345678901234");
        request.setExpiryMonth(1);
        request.setExpiryYear(2028);
        request.setCurrency("USD");
        request.setAmount(BigDecimal.valueOf(500));
        request.setCvv("999");

        BankPaymentRequest result = BankPaymentMapper.from(request);

        assertThat(result.getExpiryDate()).isEqualTo("01/2028");
    }
}
