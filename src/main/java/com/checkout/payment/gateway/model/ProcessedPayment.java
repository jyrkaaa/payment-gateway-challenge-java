package com.checkout.payment.gateway.model;

public record ProcessedPayment(PostPaymentResponse response, boolean replayed) {
}