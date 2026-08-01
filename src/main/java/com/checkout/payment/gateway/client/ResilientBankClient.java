package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.exception.BankServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ResilientBankClient implements IBankClient {

    private final IBankClient delegate;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    @Override
    public BankPaymentResponse sendPayment(BankPaymentRequest request) {
        try {
            return CircuitBreaker.decorateSupplier(circuitBreaker,
                    Retry.decorateSupplier(retry, () -> delegate.sendPayment(request)))
                .get();
        } catch (CallNotPermittedException ex) {
            log.warn("Acquiring bank circuit breaker is OPEN, fast-failing request");
            throw new BankServiceUnavailableException("Acquiring bank circuit breaker is open", ex);
        }
    }
}
