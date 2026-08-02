package com.checkout.payment.gateway.mappers;

import com.checkout.payment.gateway.client.BankPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentRequest;

import lombok.experimental.UtilityClass;

@UtilityClass
public class BankPaymentMapper {

    public BankPaymentRequest from(PostPaymentRequest request) {
        return BankPaymentRequest.builder()
            .cardNumber(request.getCardNumber())
            .expiryDate(request.getExpiryDate())
            .currency(request.getCurrency())
            .amount(request.getAmount().intValueExact())
            .cvv(request.getCvv())
            .build();
    }
}
