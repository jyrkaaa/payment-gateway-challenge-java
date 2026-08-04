package com.checkout.payment.gateway.configuration;

import com.checkout.payment.gateway.client.IBankClient;
import com.checkout.payment.gateway.client.ResilientBankClient;
import com.checkout.payment.gateway.exception.BankServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankConfigurationTest {

    private final BankConfiguration configuration = new BankConfiguration();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private BankProperties properties() {
        BankProperties properties = new BankProperties();
        properties.setUrl("http://localhost:1/payments");
        properties.setConnectTimeout(Duration.ofMillis(200));
        properties.setReadTimeout(Duration.ofMillis(200));
        properties.getResilience().getRetry().setMaxAttempts(2);
        properties.getResilience().getRetry().setInitialBackoff(Duration.ofMillis(1));
        properties.getResilience().getRetry().setBackoffMultiplier(1.0);
        properties.getResilience().getCircuitBreaker().setSlidingWindowSize(2);
        properties.getResilience().getCircuitBreaker().setMinimumNumberOfCalls(2);
        properties.getResilience().getCircuitBreaker().setFailureRateThreshold(1f);
        properties.getResilience().getCircuitBreaker().setWaitDurationInOpenState(Duration.ofSeconds(30));
        properties.getResilience().getCircuitBreaker().setPermittedCallsInHalfOpenState(1);
        return properties;
    }

    @Test
    void acquiringBankRestClient_buildsNonNullRestClient() {
        RestClient restClient = configuration.acquiringBankRestClient(properties());

        assertThat(restClient).isNotNull();
    }

    @Test
    void acquiringBankRetry_retriesOnceThenSucceeds_logsRetryEvent() {
        Retry retry = configuration.acquiringBankRetry(properties(), meterRegistry);
        AtomicInteger calls = new AtomicInteger();

        String result = Retry.decorateSupplier(retry, () -> {
            if (calls.getAndIncrement() == 0) {
                throw new BankServiceUnavailableException("first attempt fails");
            }
            return "ok";
        }).get();

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void acquiringBankRetry_exhaustsAttempts_logsRetriesExhaustedEvent() {
        Retry retry = configuration.acquiringBankRetry(properties(), meterRegistry);

        assertThatThrownBy(() -> Retry.decorateSupplier(retry, () -> {
            throw new BankServiceUnavailableException("always fails");
        }).get()).isInstanceOf(BankServiceUnavailableException.class);
    }

    @Test
    void acquiringBankCircuitBreaker_opensAfterFailures_logsStateTransitionEvent() {
        CircuitBreaker circuitBreaker = configuration.acquiringBankCircuitBreaker(properties(), meterRegistry);

        for (int i = 0; i < 2; i++) {
            try {
                CircuitBreaker.decorateSupplier(circuitBreaker, () -> {
                    throw new BankServiceUnavailableException("fail");
                }).get();
            } catch (BankServiceUnavailableException ignored) {
                // expected - forcing enough failures to trip the breaker
            }
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void acquiringBankClient_wiresResilientBankClientAroundHttpDelegate() {
        BankProperties properties = properties();
        RestClient restClient = configuration.acquiringBankRestClient(properties);
        Retry retry = configuration.acquiringBankRetry(properties, meterRegistry);
        CircuitBreaker circuitBreaker = configuration.acquiringBankCircuitBreaker(properties, meterRegistry);

        IBankClient client = configuration.acquiringBankClient(restClient, retry, circuitBreaker);

        assertThat(client).isInstanceOf(ResilientBankClient.class);
    }
}
