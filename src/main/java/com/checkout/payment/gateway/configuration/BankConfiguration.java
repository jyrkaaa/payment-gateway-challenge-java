package com.checkout.payment.gateway.configuration;
import lombok.extern.slf4j.Slf4j;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import io.github.resilience4j.core.IntervalFunction;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.checkout.payment.gateway.client.HttpBankClient;
import com.checkout.payment.gateway.client.IBankClient;
import com.checkout.payment.gateway.client.ResilientBankClient;
import com.checkout.payment.gateway.exception.BankServiceUnavailableException;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.event.RetryOnErrorEvent;
import io.github.resilience4j.retry.event.RetryOnRetryEvent;

@Slf4j
@Configuration
@EnableConfigurationProperties(BankProperties.class)
public class BankConfiguration {
    private final String ACQUIRING_BANK = "acquiring-bank";
    @Bean
    public RestClient acquiringBankRestClient(BankProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder()
            .baseUrl(properties.getUrl())
            .requestFactory(factory)
            .build();
    }

  @Bean
  public Retry acquiringBankRetry(BankProperties properties) {
    Retry retry = Retry.of(ACQUIRING_BANK, retryConfig(properties.getResilience().getRetry()));
    retry.getEventPublisher()
        .onRetry(this::logRetry)
        .onError(this::logRetriesExhausted);
    return retry;
  }

  @Bean
  public CircuitBreaker acquiringBankCircuitBreaker(BankProperties properties) {
    CircuitBreaker breaker = CircuitBreaker.of(
        ACQUIRING_BANK, circuitBreakerConfig(properties.getResilience().getCircuitBreaker()));
    breaker.getEventPublisher().onStateTransition(this::logStateTransition);
    return breaker;
  }

  @Bean
  public IBankClient acquiringBankClient(RestClient acquiringBankRestClient,
      Retry acquiringBankRetry, CircuitBreaker acquiringBankCircuitBreaker) {
    HttpBankClient httpClient = new HttpBankClient(acquiringBankRestClient);
    return new ResilientBankClient(
        httpClient, acquiringBankRetry, acquiringBankCircuitBreaker);
  }

  private static RetryConfig retryConfig(BankProperties.Retry properties) {
    return RetryConfig.custom()
        .maxAttempts(properties.getMaxAttempts())
        .intervalFunction(IntervalFunction.ofExponentialBackoff(
            properties.getInitialBackoff(), properties.getBackoffMultiplier()))
        .retryExceptions(BankServiceUnavailableException.class)
        .failAfterMaxAttempts(true)
        .build();
  }

  private static CircuitBreakerConfig circuitBreakerConfig(
      BankProperties.CircuitBreaker properties) {
    return CircuitBreakerConfig.custom()
        .failureRateThreshold(properties.getFailureRateThreshold())
        .slidingWindowType(SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(properties.getSlidingWindowSize())
        .minimumNumberOfCalls(properties.getMinimumNumberOfCalls())
        .waitDurationInOpenState(properties.getWaitDurationInOpenState())
        .permittedNumberOfCallsInHalfOpenState(properties.getPermittedCallsInHalfOpenState())
        .recordExceptions(BankServiceUnavailableException.class)
        .build();
  }

  private void logRetry(RetryOnRetryEvent event) {
    log.warn("Retrying acquiring bank call attempt={} cause={}",
        event.getNumberOfRetryAttempts() + 1, event.getLastThrowable());
  }

  private void logRetriesExhausted(RetryOnErrorEvent event) {
    log.error("Acquiring bank retries exhausted after {} attempts",
        event.getNumberOfRetryAttempts());
  }

  private void logStateTransition(CircuitBreakerOnStateTransitionEvent event) {
    log.warn("Acquiring bank circuit breaker {} -> {}",
        event.getStateTransition().getFromState(),
        event.getStateTransition().getToState());
  }
}
