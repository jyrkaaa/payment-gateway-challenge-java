package com.checkout.payment.gateway.idempotency;

import com.checkout.payment.gateway.model.PostPaymentResponse;

public record IdempotencyResult(PostPaymentResponse response, boolean replayed) {
}