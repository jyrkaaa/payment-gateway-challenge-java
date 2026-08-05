package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.exception.BankCommunicationException;
import com.checkout.payment.gateway.exception.BankServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetryingBankClientTest {

    @Mock IBankClient delegate;

    private ResilientBankClient resilientClient;
    private BankPaymentRequest request;

    @BeforeEach
    void setUp() {
        resilientClient = new ResilientBankClient(delegate, instantRetry(3), openCircuitBreaker());
        request = BankPaymentRequest.builder()
            .cardNumber("2222405343248877")
            .expiryDate("04/2030")
            .currency("GBP")
            .amount(10000)
            .cvv("123")
            .build();
    }

    @Test
    void firstAttemptSucceeds_returnsImmediately() {
        BankPaymentResponse response = authorizedResponse();
        when(delegate.sendPayment(request)).thenReturn(response);

        BankPaymentResponse result = resilientClient.sendPayment(request);

        assertThat(result).isEqualTo(response);
        verify(delegate, times(1)).sendPayment(request);
    }

    @Test
    void firstAttemptFails_secondSucceeds_returnsAfterOneRetry() {
        BankPaymentResponse response = authorizedResponse();
        when(delegate.sendPayment(request))
            .thenThrow(new BankServiceUnavailableException("503"))
            .thenReturn(response);

        BankPaymentResponse result = resilientClient.sendPayment(request);

        assertThat(result).isEqualTo(response);
        verify(delegate, times(2)).sendPayment(request);
    }

    @Test
    void allAttemptsExhausted_throwsBankServiceUnavailableException() {
        when(delegate.sendPayment(request)).thenThrow(new BankServiceUnavailableException("503"));

        assertThatThrownBy(() -> resilientClient.sendPayment(request))
            .isInstanceOf(BankServiceUnavailableException.class);
        verify(delegate, times(3)).sendPayment(request);
    }

    @Test
    void firstTwoAttemptsFail_thirdSucceeds() {
        BankPaymentResponse response = declinedResponse();
        when(delegate.sendPayment(request))
            .thenThrow(new BankServiceUnavailableException("503"))
            .thenThrow(new BankServiceUnavailableException("503"))
            .thenReturn(response);

        BankPaymentResponse result = resilientClient.sendPayment(request);

        assertThat(result).isEqualTo(response);
        verify(delegate, times(3)).sendPayment(request);
    }

    @Test
    void maxAttemptsOfOne_noRetryOnFailure() {
        resilientClient = new ResilientBankClient(delegate, instantRetry(1), openCircuitBreaker());
        when(delegate.sendPayment(request)).thenThrow(new BankServiceUnavailableException("503"));

        assertThatThrownBy(() -> resilientClient.sendPayment(request))
            .isInstanceOf(BankServiceUnavailableException.class);
        verify(delegate, times(1)).sendPayment(request);
    }

    @Test
    void nonRetryableException_notRetried() {
        when(delegate.sendPayment(request)).thenThrow(new BankCommunicationException("400"));

        assertThatThrownBy(() -> resilientClient.sendPayment(request))
            .isInstanceOf(BankCommunicationException.class)
            .isNotInstanceOf(BankServiceUnavailableException.class);
        verify(delegate, times(1)).sendPayment(request);
    }

    @Test
    void circuitBreakerOpen_fastFailsWithoutCallingDelegate() {
        CircuitBreaker openCircuitBreaker = CircuitBreaker.ofDefaults("open-test");
        openCircuitBreaker.transitionToOpenState();
        resilientClient = new ResilientBankClient(delegate, instantRetry(3), openCircuitBreaker);

        assertThatThrownBy(() -> resilientClient.sendPayment(request))
            .isInstanceOf(BankServiceUnavailableException.class)
            .hasMessageContaining("circuit breaker is open");
        verify(delegate, never()).sendPayment(request);
    }

    private static Retry instantRetry(int maxAttempts) {
        return Retry.of("test", RetryConfig.custom()
            .maxAttempts(maxAttempts)
            .waitDuration(Duration.ZERO)
            .retryExceptions(BankServiceUnavailableException.class)
            .failAfterMaxAttempts(true)
            .build());
    }

    private static CircuitBreaker openCircuitBreaker() {
        return CircuitBreaker.ofDefaults("test");
    }

    private BankPaymentResponse authorizedResponse() {
        BankPaymentResponse r = new BankPaymentResponse();
        r.setAuthorized(true);
        r.setAuthorizationCode("abc-123");
        return r;
    }

    private BankPaymentResponse declinedResponse() {
        BankPaymentResponse r = new BankPaymentResponse();
        r.setAuthorized(false);
        r.setAuthorizationCode("");
        return r;
    }
}
