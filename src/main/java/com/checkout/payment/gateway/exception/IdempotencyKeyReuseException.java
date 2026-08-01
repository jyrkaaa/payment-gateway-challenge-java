package com.checkout.payment.gateway.exception;

public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException(String key) {
        super("Idempotency key '" + key + "' was already used with a different request");
    }
}
