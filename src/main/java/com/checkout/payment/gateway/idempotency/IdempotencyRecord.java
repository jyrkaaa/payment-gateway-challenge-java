package com.checkout.payment.gateway.idempotency;

import com.checkout.payment.gateway.model.PostPaymentResponse;
import java.time.Instant;

public record IdempotencyRecord(
    String fingerprint,
    PostPaymentResponse response,
    Instant storedAt) {
}
