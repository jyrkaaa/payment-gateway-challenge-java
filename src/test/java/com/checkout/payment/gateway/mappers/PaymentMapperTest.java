package com.checkout.payment.gateway.mappers;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMapperTest {

    @Test
    void toPaymentResponse_mapsAllFields() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCardNumber("2222405343248877");
        request.setExpiryMonth(4);
        request.setExpiryYear(2030);
        request.setCurrency("GBP");
        request.setAmount(10000);

        UUID id = UUID.randomUUID();
        PostPaymentResponse response = PaymentMapper.toPaymentResponse(id, PaymentStatus.AUTHORIZED, request);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(response.getCardNumberLastFour()).isEqualTo("8877");
        assertThat(response.getExpiryMonth()).isEqualTo(4);
        assertThat(response.getExpiryYear()).isEqualTo(2030);
        assertThat(response.getCurrency()).isEqualTo("GBP");
        assertThat(response.getAmount()).isEqualTo(10000);
    }

    @Test
    void toPaymentResponse_declinedStatus_isPreserved() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCardNumber("2222405343248872");
        request.setExpiryMonth(12);
        request.setExpiryYear(2025);
        request.setCurrency("USD");
        request.setAmount(500);

        PostPaymentResponse response = PaymentMapper.toPaymentResponse(UUID.randomUUID(), PaymentStatus.DECLINED, request);

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(response.getCardNumberLastFour()).isEqualTo("8872");
    }

    @Test
    void toPaymentResponse_lastFourDigitsWithLeadingZero_preservesLeadingZero() {
        PostPaymentRequest request = new PostPaymentRequest();
        request.setCardNumber("2222405343240004");
        request.setExpiryMonth(4);
        request.setExpiryYear(2030);
        request.setCurrency("GBP");
        request.setAmount(100);

        PostPaymentResponse response = PaymentMapper.toPaymentResponse(UUID.randomUUID(), PaymentStatus.AUTHORIZED, request);

        assertThat(response.getCardNumberLastFour()).isEqualTo("0004");
    }
}
