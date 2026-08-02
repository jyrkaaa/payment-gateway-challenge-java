package com.checkout.payment.gateway.configuration;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.acquiring-bank")
public class BankProperties {

  private String url;
  private Duration connectTimeout = Duration.ofSeconds(2);
  private Duration readTimeout = Duration.ofSeconds(5);

  private final Resilience resilience = new Resilience();

  @Getter
  @Setter
  public static class Resilience {
    private final Retry retry = new Retry();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();
  }

  @Getter
  @Setter
  public static class Retry {
    private int maxAttempts = 3;
    private Duration initialBackoff = Duration.ofMillis(2000);
    private double backoffMultiplier = 2.0;
  }

  @Getter
  @Setter
  public static class CircuitBreaker {
    private float failureRateThreshold = 50f;
    private int slidingWindowSize = 20;
    private int minimumNumberOfCalls = 10;
    private Duration waitDurationInOpenState = Duration.ofSeconds(30);
    private int permittedCallsInHalfOpenState = 3;
  }
}