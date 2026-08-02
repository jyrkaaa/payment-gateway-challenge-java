package com.checkout.payment.gateway.mappers;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;

import lombok.experimental.UtilityClass;

import java.util.UUID;

@UtilityClass
public class PaymentMapper {
    public PostPaymentResponse toPaymentResponse(UUID id, PaymentStatus status, PostPaymentRequest request) {
        PostPaymentResponse response = new PostPaymentResponse();
        response.setId(id);
        response.setStatus(status);
        response.setCardNumberLastFour(request.getCardNumberLastFour());
        response.setExpiryMonth(request.getExpiryMonth());
        response.setExpiryYear(request.getExpiryYear());
        response.setCurrency(request.getCurrency());
        response.setAmount(request.getAmount());
        return response;
    }
}
