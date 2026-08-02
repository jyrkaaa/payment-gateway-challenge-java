# Payment Gateway — Implementation Steps

---

## Idempotency Analysis

### Is idempotency required for this payment gateway?

**Short answer:** Yes, in production — but not implemented for this time-boxed challenge.

**Why it matters:**

A merchant submits a `POST /payments` request. The gateway successfully charges the customer and stores the result, but the response is lost due to a network timeout before reaching the merchant. The merchant retries the same request — without idempotency protection, the customer is charged twice.

This is the canonical double-charge problem in payments, and it's why payment APIs like Stripe and Adyen mandate an `Idempotency-Key` header on all mutating requests.

**Production approach (`Idempotency-Key` header pattern):**

1. Merchant generates a unique UUID per payment *attempt* and sends it as `Idempotency-Key: <uuid>`.
2. Gateway checks a store (Redis / DB) for a previous response against that key.
3. If found: return the stored response immediately — no bank call.
4. If not found: process the payment, store the response keyed by idempotency key, then return it.
5. TTL of 24 hours is typical; expired keys are treated as new attempts.

**Why not implemented here:**
- The docs don't require it, the bank simulator is not idempotent by design (each call may produce a different `authorization_code`), and the in-memory storage is ephemeral anyway (restarted between tests).
- Implementing it correctly requires atomic read-then-write (e.g. Redis `SET NX EX`) to avoid a TOCTOU race; simulating this correctly with a HashMap introduces complexity that distracts from the core assessment.

**Documented assumption in README:** "No idempotency key support — merchants should not retry `POST /payments` on timeout. Production deployments would require an `Idempotency-Key` header and a durable response store."

---

## Step 1: Add dependencies (`build.gradle`)

```gradle
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.1.5'
    id 'io.spring.dependency-management' version '1.0.15.RELEASE'
}

group = 'com.checkout'
version = '0.0.1-SNAPSHOT'

java {
    sourceCompatibility = '17'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-validation'       // @Valid, @NotBlank, etc.
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.2.0'
    implementation 'io.micrometer:micrometer-registry-prometheus'                  // Prometheus metrics → Grafana
    implementation 'net.logstash.logback:logstash-logback-encoder:7.4'            // Structured JSON logs → Loki/ELK
    compileOnly     'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.wiremock:wiremock-standalone:3.3.1'
    testCompileOnly     'org.projectlombok:lombok'
    testAnnotationProcessor 'org.projectlombok:lombok'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

**Lombok** removes all boilerplate getters, setters, constructors, and `toString`. Applied to every model/DTO class.

**`spring-boot-starter-validation`** enables Jakarta Bean Validation. Used on `PostPaymentRequest` for simple field constraints; a custom validator handles the compound expiry rule.

**`micrometer-registry-prometheus`** exposes `/actuator/prometheus` — scraped by Prometheus and visualised in Grafana.

**`logstash-logback-encoder`** outputs structured JSON logs, digestible by Grafana Loki (or any ELK stack).

---

## Step 2: Configure `application.properties`

```properties
server.port=8090
springdoc.swagger-ui.enabled=true
springdoc.api-docs.enabled=true

# Bank simulator (override in tests via @SpringBootTest properties)
bank.simulator.url=http://localhost:8080/payments

# Actuator — exposes /actuator/health, /actuator/info, /actuator/metrics, /actuator/prometheus
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=always
management.metrics.tags.application=payment-gateway           # global tag on every metric
management.info.env.enabled=true
info.app.name=Payment Gateway
info.app.version=1.0.0

# In dev, pattern-based logging is readable in the terminal.
# In production, logback-spring.xml switches to JSON (see Step 2c).
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss} [%thread] [%X{correlationId:-no-corr}] [%X{paymentId:-}] %-5level %logger{36} - %msg%n
```

---

## Step 2b: Unified SLF4J Logging with MDC Correlation IDs

Every layer (controller, service, bank client) uses SLF4J. The unifying mechanism is an MDC (Mapped Diagnostic Context) servlet filter that attaches a `correlationId` to every log entry for the duration of a single HTTP request. This makes it trivial to grep all log lines belonging to one payment flow.

Example log output with correlation and payment IDs:
```
2026-08-01 12:00:01 [http-nio-8090-exec-1] [a3f1-...] []         INFO  c.c.p.g.controller.PaymentGatewayController - POST /payments received
2026-08-01 12:00:01 [http-nio-8090-exec-1] [a3f1-...] []         DEBUG c.c.p.g.client.BankClient - Sending payment to bank for card ending 8877
2026-08-01 12:00:01 [http-nio-8090-exec-1] [a3f1-...] [f9d2-...] INFO  c.c.p.g.service.PaymentGatewayService - Payment f9d2-... processed with status Authorized for card ending 8877
```

### Create `RequestCorrelationFilter.java` — new package `filter/`

```java
package com.checkout.payment.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Component
public class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = Optional.ofNullable(request.getHeader(CORRELATION_HEADER))
            .filter(h -> !h.isBlank())
            .orElse(UUID.randomUUID().toString());

        MDC.put("correlationId", correlationId);
        response.setHeader(CORRELATION_HEADER, correlationId);   // echo back to merchant

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();   // must clear — servlet containers reuse threads
        }
    }
}
```

**Why `OncePerRequestFilter`:** Spring may dispatch multiple times (async, error forwarding); this ensures MDC is set exactly once per logical request.

**Why `MDC.clear()` in finally:** Not clearing leaks correlation IDs from previous requests into unrelated log entries on pooled threads.

### Add `paymentId` to MDC in `PaymentGatewayService`

Once the payment ID is assigned, push it into MDC so all subsequent log entries (bank client, exception handler) carry it automatically:

```java
UUID paymentId = UUID.randomUUID();
MDC.put("paymentId", paymentId.toString());
// ... build response, call bank, store ...
LOG.info("Payment {} processed with status {} for card ending {}",
    paymentId, status.getName(), request.getCardNumberLastFour());
// MDC cleared automatically when the request completes via RequestCorrelationFilter
```

### Logging contract — what each layer logs

| Layer | Level | What to include | Never log |
|-------|-------|-----------------|-----------|
| Filter | DEBUG | correlationId assigned vs received | — |
| Controller | INFO | HTTP method, path | Request body |
| Service | INFO | paymentId, status, card last four | Full card number, CVV |
| Service | WARN | Validation error messages | Card number |
| BankClient | DEBUG | Card last four, authorized=true/false | Full card number, CVV |
| BankClient | WARN | 503 received, card last four | — |
| ExceptionHandler | WARN/ERROR | Exception message | Full stack trace at WARN |

---

## Step 2c: Production-Grade Observability — Prometheus Metrics + JSON Logs → Grafana

The standard Java observability stack for Grafana is:

```
Spring Boot (Micrometer)  →  Prometheus  →  Grafana dashboards
Spring Boot (Logback JSON) →  Grafana Loki  →  Grafana log exploration
```

### Structured JSON Logging (`logback-spring.xml`)

Create `src/main/resources/logback-spring.xml`. Pattern-based logging stays active in the `default` (dev) profile; JSON activates under `production`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- Dev profile: human-readable console output -->
    <springProfile name="default">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] [%X{correlationId:-no-corr}] [%X{paymentId:-}] %-5level %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>

    <!-- Production profile: structured JSON consumed by Grafana Loki -->
    <springProfile name="production">
        <appender name="JSON_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <!-- Include MDC fields automatically -->
                <includeMdcKeyName>correlationId</includeMdcKeyName>
                <includeMdcKeyName>paymentId</includeMdcKeyName>
                <!-- Static app label visible in Grafana Loki queries -->
                <customFields>{"app":"payment-gateway","env":"production"}</customFields>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="JSON_CONSOLE"/>
        </root>
    </springProfile>
</configuration>
```

**How to activate production logging:**
```bash
./gradlew bootRun --args='--spring.profiles.active=production'
# or via env var:
SPRING_PROFILES_ACTIVE=production java -jar payment-gateway.jar
```

Grafana Loki queries on these logs:
```logql
{app="payment-gateway"} | json | correlationId = "a3f1-..."
{app="payment-gateway"} | json | level = "ERROR"
{app="payment-gateway"} | json | paymentId != "" | json
```

---

### Custom Micrometer Metrics (Prometheus → Grafana)

Inject `MeterRegistry` into `PaymentGatewayService` and record counters + timers:

```java
@Service
public class PaymentGatewayService {

    private final Counter authorizedCounter;
    private final Counter declinedCounter;
    private final Counter rejectedCounter;
    private final Counter bankErrorCounter;
    private final Timer   processingTimer;

    public PaymentGatewayService(PaymentsRepository repository,
                                 PaymentRequestValidator validator,
                                 BankClient bankClient,
                                 MeterRegistry meterRegistry) {
        this.authorizedCounter = Counter.builder("payments.processed")
            .tag("status", "authorized")
            .description("Total authorized payments")
            .register(meterRegistry);
        this.declinedCounter = Counter.builder("payments.processed")
            .tag("status", "declined")
            .register(meterRegistry);
        this.rejectedCounter = Counter.builder("payments.rejected")
            .description("Payments rejected by gateway validation (bank not called)")
            .register(meterRegistry);
        this.bankErrorCounter = Counter.builder("payments.bank.errors")
            .description("Bank simulator returned 503 or other error")
            .register(meterRegistry);
        this.processingTimer = Timer.builder("payments.processing.duration")
            .description("End-to-end payment processing time including bank call")
            .register(meterRegistry);
    }

    public PostPaymentResponse processPayment(PostPaymentRequest request) {
        return processingTimer.record(() -> {
            List<String> errors = validator.validate(request);
            if (!errors.isEmpty()) {
                rejectedCounter.increment();
                throw new PaymentValidationException(errors);
            }

            Optional<BankPaymentResponse> bankResp;
            try {
                bankResp = bankClient.sendPayment(request);
            } catch (BankCommunicationException e) {
                bankErrorCounter.increment();
                throw e;
            }

            if (bankResp.isEmpty()) {
                bankErrorCounter.increment();
                throw new BankCommunicationException("Bank unavailable", null);
            }

            PaymentStatus status = bankResp.get().isAuthorized()
                ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED;

            if (status == PaymentStatus.AUTHORIZED) authorizedCounter.increment();
            else declinedCounter.increment();

            // ... build response, persist, log ...
        });
    }
}
```

These metrics appear at `/actuator/prometheus` in Prometheus exposition format:
```
payments_processed_total{application="payment-gateway",status="authorized"} 42.0
payments_processed_total{application="payment-gateway",status="declined"} 7.0
payments_rejected_total{application="payment-gateway"} 3.0
payments_bank_errors_total{application="payment-gateway"} 1.0
payments_processing_duration_seconds_count{application="payment-gateway"} 49.0
payments_processing_duration_seconds_sum{application="payment-gateway"} 12.37
```

---

### Docker Compose: Prometheus + Grafana

Extend `docker-compose.yml` with a local Prometheus + Grafana stack to visualise metrics during development:

```yaml
  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
    depends_on:
      - prometheus
```

Create `prometheus.yml` at project root:

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'payment-gateway'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8090']   # MacOS/Windows; use 'localhost' on Linux
```

After `docker-compose up`, import a Grafana dashboard at `http://localhost:3000` (admin/admin):
- Add Prometheus datasource: `http://prometheus:9090`
- Build panels for: `rate(payments_processed_total[5m])`, `payments_processing_duration_seconds` histogram, `payments_rejected_total`, error rate

**Suggested Grafana panel queries:**
```promql
# Payment throughput (per second, 5m window)
rate(payments_processed_total{application="payment-gateway"}[5m])

# P95 processing latency
histogram_quantile(0.95, rate(payments_processing_duration_seconds_bucket[5m]))

# Error rate (bank errors + rejections)
rate(payments_bank_errors_total[5m]) + rate(payments_rejected_total[5m])
```

---

## Step 3: Fix `PostPaymentRequest.java` (blocking bug + Lombok + Bean Validation)

The boilerplate has a **bug**: `cardNumberLastFour` (int) instead of the full card number. Rewrite using Lombok and Jakarta Bean Validation annotations.

```java
package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.validation.ValidExpiryDate;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data                  // Lombok: generates getters, setters, equals, hashCode, toString
@ValidExpiryDate       // Custom class-level constraint — validates month+year combination (see Step 7)
public class PostPaymentRequest {

    @NotBlank(message = "card_number is required")
    @Pattern(regexp = "\\d{14,19}", message = "card_number must be 14-19 numeric characters")
    @JsonProperty("card_number")
    private String cardNumber;

    @Min(value = 1, message = "expiry_month must be between 1 and 12")
    @Max(value = 12, message = "expiry_month must be between 1 and 12")
    @JsonProperty("expiry_month")
    private int expiryMonth;

    @JsonProperty("expiry_year")
    private int expiryYear;    // range validated by @ValidExpiryDate compound constraint

    @NotBlank(message = "currency is required")
    @Size(min = 3, max = 3, message = "currency must be 3 characters")
    private String currency;   // allowed values validated by PaymentRequestValidator

    @Positive(message = "amount must be a positive integer")
    private int amount;

    @NotBlank(message = "cvv is required")
    @Pattern(regexp = "\\d{3,4}", message = "cvv must be 3-4 numeric characters")
    private String cvv;        // String not int — preserves leading zeros (e.g. "007")

    // Not JSON properties — helpers for building the bank request
    public String getExpiryDate() {
        return String.format("%02d/%d", expiryMonth, expiryYear);  // zero-padded: "04/2025"
    }

    public String getCardNumberLastFour() {
        return cardNumber != null && cardNumber.length() >= 4
            ? cardNumber.substring(cardNumber.length() - 4)
            : cardNumber;
    }
}
```

`@Data` from Lombok eliminates all manual getter/setter/toString boilerplate. The two hand-written helpers compute derived values rather than returning fields directly, so Lombok doesn't interfere with them.

---

## Step 4: Rewrite `PostPaymentResponse.java` with Lombok + `@JsonProperty`

Delete all hand-written getters/setters/toString. Add Lombok and snake_case annotations.

```java
package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.UUID;

@Data
public class PostPaymentResponse {
    @JsonProperty("id")                    private UUID         id;
    @JsonProperty("status")               private PaymentStatus status;
    @JsonProperty("card_number_last_four") private int          cardNumberLastFour;
    @JsonProperty("expiry_month")          private int          expiryMonth;
    @JsonProperty("expiry_year")           private int          expiryYear;
    @JsonProperty("currency")              private String       currency;
    @JsonProperty("amount")                private int          amount;
}
```

---

## Step 5: Delete `GetPaymentResponse.java`

This class is an exact duplicate of `PostPaymentResponse` with no production references. Delete it.

```bash
rm src/main/java/com/checkout/payment/gateway/model/GetPaymentResponse.java
```

---

## Step 6: Create bank client — new package `client/`

### 6a. `BankPaymentRequest.java`

Outbound DTO sent to the bank simulator. Separate from the merchant-facing DTO so the bank contract can evolve independently. Uses Lombok `@Builder` for clean static factory construction.

```java
package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BankPaymentRequest {

    @JsonProperty("card_number")  private String cardNumber;
    @JsonProperty("expiry_date")  private String expiryDate;
    @JsonProperty("currency")     private String currency;
    @JsonProperty("amount")       private int    amount;
    @JsonProperty("cvv")          private String cvv;

    public static BankPaymentRequest from(PostPaymentRequest request) {
        return BankPaymentRequest.builder()
            .cardNumber(request.getCardNumber())
            .expiryDate(request.getExpiryDate())   // "04/2025" zero-padded
            .currency(request.getCurrency())
            .amount(request.getAmount())
            .cvv(request.getCvv())
            .build();
    }
}
```

### 6b. `BankPaymentResponse.java`

```java
package com.checkout.payment.gateway.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class BankPaymentResponse {
    @JsonProperty("authorized")          private boolean authorized;
    @JsonProperty("authorization_code")  private String  authorizationCode;
}
```

### 6c. `BankClient.java`

```java
package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.exception.BankCommunicationException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

@Component
public class BankClient {

    private static final Logger LOG = LoggerFactory.getLogger(BankClient.class);

    private final RestTemplate restTemplate;
    private final String bankUrl;

    public BankClient(RestTemplate restTemplate, @Value("${bank.simulator.url}") String bankUrl) {
        this.restTemplate = restTemplate;
        this.bankUrl = bankUrl;
    }

    /**
     * Returns empty Optional when bank is unavailable (503).
     * Throws BankCommunicationException for other unexpected errors.
     */
    public Optional<BankPaymentResponse> sendPayment(PostPaymentRequest request) {
        BankPaymentRequest bankRequest = BankPaymentRequest.from(request);
        try {
            LOG.debug("Sending payment to bank for card ending {}", request.getCardNumberLastFour());
            ResponseEntity<BankPaymentResponse> response =
                restTemplate.postForEntity(bankUrl, bankRequest, BankPaymentResponse.class);
            LOG.debug("Bank responded: authorized={}", response.getBody() != null && response.getBody().isAuthorized());
            return Optional.ofNullable(response.getBody());
        } catch (HttpServerErrorException.ServiceUnavailable e) {
            LOG.warn("Bank simulator returned 503 for card ending {}", request.getCardNumberLastFour());
            return Optional.empty();
        } catch (Exception e) {
            throw new BankCommunicationException("Unexpected error communicating with bank", e);
        }
    }
}
```

---

## Step 7: Create validation — new package `validation/`

Validation has two tiers that work together:
- **Bean Validation annotations** on `PostPaymentRequest` (field-level, wired by `@Valid` in the controller) handle simple constraints.
- **`PaymentRequestValidator`** (called from the service) handles compound rules that Bean Validation can't express with a single annotation: currency allowlist and the compound expiry month+year future check.
- **`@ValidExpiryDate`** custom Bean Validation constraint handles the compound expiry rule at the DTO level.

### `ValidExpiryDate.java` — custom constraint annotation

```java
package com.checkout.payment.gateway.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ExpiryDateValidator.class)
public @interface ValidExpiryDate {
    String message() default "Card expiry date must be in the future";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

### `ExpiryDateValidator.java` — constraint implementation

```java
package com.checkout.payment.gateway.validation;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.YearMonth;

public class ExpiryDateValidator implements ConstraintValidator<ValidExpiryDate, PostPaymentRequest> {

    @Override
    public boolean isValid(PostPaymentRequest request, ConstraintValidatorContext context) {
        if (request == null) return true;
        int month = request.getExpiryMonth();
        int year  = request.getExpiryYear();
        if (month < 1 || month > 12) return true;  // already caught by @Min/@Max on the field
        try {
            return YearMonth.of(year, month).isAfter(YearMonth.now());
        } catch (Exception e) {
            return false;
        }
    }
}
```

### `PaymentRequestValidator.java` — currency allowlist check

Simple component handling the currency allowlist rule (can't be expressed as a standard Bean Validation annotation without a custom one):

```java
package com.checkout.payment.gateway.validation;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class PaymentRequestValidator {

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "GBP", "EUR");

    public List<String> validate(PostPaymentRequest request) {
        List<String> errors = new ArrayList<>();
        if (request.getCurrency() != null && request.getCurrency().length() == 3
                && !SUPPORTED_CURRENCIES.contains(request.getCurrency().toUpperCase())) {
            errors.add("currency must be one of: USD, GBP, EUR");
        }
        return errors;
    }
}
```

> **Why split?** The `@Valid` / Bean Validation annotations on `PostPaymentRequest` fire first (in the controller) and handle structural checks fast. `PaymentRequestValidator` runs in the service for semantic checks (currency allowlist). This avoids a bank call even if Bean Validation somehow passes a bad currency.

---

## Step 8: Add exception types

### `PaymentValidationException.java`

```java
package com.checkout.payment.gateway.exception;

import java.util.List;

public class PaymentValidationException extends RuntimeException {

    private final List<String> errors;

    public PaymentValidationException(List<String> errors) {
        super("Payment validation failed: " + errors);
        this.errors = errors;
    }

    public List<String> getErrors() { return errors; }
}
```

### `BankCommunicationException.java`

```java
package com.checkout.payment.gateway.exception;

public class BankCommunicationException extends RuntimeException {

    public BankCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

---

## Step 9: Update `CommonExceptionHandler.java`

Add three new handlers alongside the existing `EventProcessingException` handler:

```java
// Bean Validation (@Valid) constraint violations — fires from controller before service is called
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex) {
    List<String> errors = ex.getBindingResult().getFieldErrors().stream()
        .map(fe -> fe.getDefaultMessage())
        .toList();
    // Also capture class-level constraint violations (@ValidExpiryDate)
    ex.getBindingResult().getGlobalErrors().stream()
        .map(ge -> ge.getDefaultMessage())
        .forEach(errors::add);
    LOG.warn("Payment rejected by Bean Validation: {}", errors);
    return ResponseEntity.unprocessableEntity()
        .body(new ErrorResponse(String.join("; ", errors)));
}

// Service-level semantic validation (currency allowlist etc.)
@ExceptionHandler(PaymentValidationException.class)
public ResponseEntity<ErrorResponse> handleValidation(PaymentValidationException ex) {
    LOG.warn("Payment rejected: {}", ex.getErrors());
    return ResponseEntity.unprocessableEntity()
        .body(new ErrorResponse(String.join("; ", ex.getErrors())));
}

@ExceptionHandler(BankCommunicationException.class)
public ResponseEntity<ErrorResponse> handleBankUnavailable(BankCommunicationException ex) {
    LOG.error("Bank unavailable", ex);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(new ErrorResponse("Acquiring bank is currently unavailable"));
}
```

---

## Step 10: Implement `PaymentGatewayService.processPayment`

Change the return type from `UUID` to `PostPaymentResponse`. Inject `PaymentRequestValidator` and `BankClient`.

```java
@Service
public class PaymentGatewayService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayService.class);

    private final PaymentsRepository paymentsRepository;
    private final PaymentRequestValidator validator;
    private final BankClient bankClient;

    public PaymentGatewayService(PaymentsRepository paymentsRepository,
                                 PaymentRequestValidator validator,
                                 BankClient bankClient) {
        this.paymentsRepository = paymentsRepository;
        this.validator = validator;
        this.bankClient = bankClient;
    }

    public PostPaymentResponse getPaymentById(UUID id) {
        LOG.debug("Retrieving payment {}", id);
        return paymentsRepository.get(id)
            .orElseThrow(() -> new EventProcessingException("Payment not found: " + id));
    }

    public PostPaymentResponse processPayment(PostPaymentRequest request) {
        List<String> errors = validator.validate(request);
        if (!errors.isEmpty()) {
            LOG.warn("Rejecting payment for card ending {} — validation errors: {}",
                request.getCardNumberLastFour(), errors);
            throw new PaymentValidationException(errors);
        }

        Optional<BankPaymentResponse> bankResp = bankClient.sendPayment(request);

        PaymentStatus status = bankResp
            .map(r -> r.isAuthorized() ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED)
            .orElseThrow(() -> new BankCommunicationException("Bank unavailable", null));

        PostPaymentResponse response = new PostPaymentResponse();
        response.setId(UUID.randomUUID());
        response.setStatus(status);
        response.setCardNumberLastFour(Integer.parseInt(request.getCardNumberLastFour()));
        response.setExpiryMonth(request.getExpiryMonth());
        response.setExpiryYear(request.getExpiryYear());
        response.setCurrency(request.getCurrency());
        response.setAmount(request.getAmount());

        paymentsRepository.add(response);

        LOG.info("Payment {} processed with status {} for card ending {}",
            response.getId(), status.getName(), request.getCardNumberLastFour());

        return response;
    }
}
```

---

## Step 11: Update `PaymentGatewayController.java`

Fix path to `/payments` (plural), add POST endpoint, add OpenAPI annotations.

```java
package com.checkout.payment.gateway.controller;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.service.PaymentGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/payments")
@Tag(name = "Payments", description = "Payment processing and retrieval")
public class PaymentGatewayController {

    private final PaymentGatewayService paymentGatewayService;

    public PaymentGatewayController(PaymentGatewayService paymentGatewayService) {
        this.paymentGatewayService = paymentGatewayService;
    }

    @Operation(summary = "Process a payment",
               description = "Submits a card payment to the acquiring bank. Returns Authorized or Declined.")
    @ApiResponse(responseCode = "201", description = "Payment processed (Authorized or Declined)")
    @ApiResponse(responseCode = "422", description = "Payment rejected — invalid input, bank was not called")
    @ApiResponse(responseCode = "502", description = "Acquiring bank unavailable")
    @PostMapping
    public ResponseEntity<PostPaymentResponse> processPayment(@Valid @RequestBody PostPaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(paymentGatewayService.processPayment(request));
    }

    @Operation(summary = "Retrieve a payment by ID")
    @ApiResponse(responseCode = "200", description = "Payment found")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    @GetMapping("/{id}")
    public ResponseEntity<PostPaymentResponse> getPaymentById(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentGatewayService.getPaymentById(id));
    }
}
```

---

## Step 12: Fix `PaymentGatewayControllerTest.java`

Update two things in the existing tests:
- Path: `/payment/` → `/payments/`
- JSON assertion key: `$.cardNumberLastFour` → `$.card_number_last_four`

---

## Step 13: Create validation tests

### `PaymentRequestValidatorTest.java` — currency allowlist unit test

Pure unit test for the `PaymentRequestValidator` (currency allowlist only; Bean Validation rules tested via controller integration tests):

```java
package com.checkout.payment.gateway.validation;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRequestValidatorTest {

    private PaymentRequestValidator validator;

    @BeforeEach
    void setUp() { validator = new PaymentRequestValidator(); }

    @Test void unsupportedCurrency_CAD_returnsError() {
        PostPaymentRequest r = new PostPaymentRequest();
        r.setCurrency("CAD");
        assertThat(validator.validate(r)).anyMatch(e -> e.contains("currency"));
    }

    @Test void supportedCurrency_USD_noError() {
        PostPaymentRequest r = new PostPaymentRequest();
        r.setCurrency("USD");
        assertThat(validator.validate(r)).isEmpty();
    }

    @Test void supportedCurrency_GBP_noError() {
        PostPaymentRequest r = new PostPaymentRequest();
        r.setCurrency("GBP");
        assertThat(validator.validate(r)).isEmpty();
    }

    @Test void supportedCurrency_EUR_noError() {
        PostPaymentRequest r = new PostPaymentRequest();
        r.setCurrency("EUR");
        assertThat(validator.validate(r)).isEmpty();
    }
}
```

### `ExpiryDateValidatorTest.java` — custom constraint unit test

```java
package com.checkout.payment.gateway.validation;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class ExpiryDateValidatorTest {

    private ExpiryDateValidator validator;

    @BeforeEach
    void setUp() { validator = new ExpiryDateValidator(); }

    private PostPaymentRequest request(int month, int year) {
        PostPaymentRequest r = new PostPaymentRequest();
        r.setExpiryMonth(month);
        r.setExpiryYear(year);
        return r;
    }

    @Test void pastDate_invalid() {
        assertThat(validator.isValid(request(1, 2020), null)).isFalse();
    }

    @Test void currentMonthYear_invalid() {
        YearMonth now = YearMonth.now();
        assertThat(validator.isValid(request(now.getMonthValue(), now.getYear()), null)).isFalse();
    }

    @Test void nextMonth_valid() {
        YearMonth next = YearMonth.now().plusMonths(1);
        assertThat(validator.isValid(request(next.getMonthValue(), next.getYear()), null)).isTrue();
    }

    @Test void futureYear_valid() {
        assertThat(validator.isValid(request(4, 2030), null)).isTrue();
    }
}


---

## Step 14: Create `PaymentGatewayServiceTest.java`

Mockito unit test — mocks repository, bank client, and validator.

```java
package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankPaymentResponse;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.BankCommunicationException;
import com.checkout.payment.gateway.exception.EventProcessingException;
import com.checkout.payment.gateway.exception.PaymentValidationException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import com.checkout.payment.gateway.validation.PaymentRequestValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentGatewayServiceTest {

    @Mock PaymentsRepository repository;
    @Mock BankClient bankClient;
    @Mock PaymentRequestValidator validator;

    private PaymentGatewayService service;

    @BeforeEach
    void setUp() { service = new PaymentGatewayService(repository, validator, bankClient); }

    @Test
    void processPayment_validRequest_authorized_storesAndReturnsAuthorized() {
        PostPaymentRequest request = aValidRequest();
        when(validator.validate(request)).thenReturn(List.of());
        BankPaymentResponse bankResp = mock(BankPaymentResponse.class);
        when(bankResp.isAuthorized()).thenReturn(true);
        when(bankClient.sendPayment(request)).thenReturn(Optional.of(bankResp));

        PostPaymentResponse result = service.processPayment(request);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        verify(repository).add(result);
    }

    @Test
    void processPayment_validRequest_declined_storesAndReturnsDeclined() {
        PostPaymentRequest request = aValidRequest();
        when(validator.validate(request)).thenReturn(List.of());
        BankPaymentResponse bankResp = mock(BankPaymentResponse.class);
        when(bankResp.isAuthorized()).thenReturn(false);
        when(bankClient.sendPayment(request)).thenReturn(Optional.of(bankResp));

        PostPaymentResponse result = service.processPayment(request);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        verify(repository).add(result);
    }

    @Test
    void processPayment_validationErrors_throwsValidationException_bankNeverCalled() {
        PostPaymentRequest request = aValidRequest();
        when(validator.validate(request)).thenReturn(List.of("card_number is required"));

        assertThatThrownBy(() -> service.processPayment(request))
            .isInstanceOf(PaymentValidationException.class);
        verifyNoInteractions(bankClient);
        verifyNoInteractions(repository);
    }

    @Test
    void processPayment_bankUnavailable_throwsBankCommunicationException() {
        PostPaymentRequest request = aValidRequest();
        when(validator.validate(request)).thenReturn(List.of());
        when(bankClient.sendPayment(request)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processPayment(request))
            .isInstanceOf(BankCommunicationException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void getPaymentById_knownId_returnsPayment() {
        PostPaymentResponse payment = new PostPaymentResponse();
        payment.setId(UUID.randomUUID());
        when(repository.get(payment.getId())).thenReturn(Optional.of(payment));

        assertThat(service.getPaymentById(payment.getId())).isEqualTo(payment);
    }

    @Test
    void getPaymentById_unknownId_throwsEventProcessingException() {
        UUID id = UUID.randomUUID();
        when(repository.get(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPaymentById(id))
            .isInstanceOf(EventProcessingException.class);
    }

    private PostPaymentRequest aValidRequest() {
        PostPaymentRequest r = new PostPaymentRequest();
        r.setCardNumber("2222405343248877");
        r.setExpiryMonth(4);
        r.setExpiryYear(2030);
        r.setCurrency("GBP");
        r.setAmount(100);
        r.setCvv("123");
        return r;
    }
}
```

---

## Step 15: Create `ProcessPaymentControllerTest.java`

WireMock integration test — overrides bank URL to point at a WireMock server.

```java
package com.checkout.payment.gateway.controller;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProcessPaymentControllerTest {

    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() { wireMock.stop(); }

    @BeforeEach
    void resetWireMock() { wireMock.resetAll(); }

    @DynamicPropertySource
    static void overrideBankUrl(DynamicPropertyRegistry registry) {
        registry.add("bank.simulator.url", () -> "http://localhost:" + wireMock.port() + "/payments");
    }

    @Autowired MockMvc mvc;

    @Test
    void processPayment_cardEndingOdd_returns201Authorized() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/payments"))
            .willReturn(okJson("{\"authorized\":true,\"authorization_code\":\"abc-123\"}")));

        mvc.perform(post("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248877","expiry_month":4,"expiry_year":2030,
                     "currency":"GBP","amount":100,"cvv":"123"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("Authorized"))
            .andExpect(jsonPath("$.card_number_last_four").value(8877))
            .andExpect(jsonPath("$.currency").value("GBP"))
            .andExpect(jsonPath("$.amount").value(100))
            .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void processPayment_cardEndingEven_returns201Declined() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/payments"))
            .willReturn(okJson("{\"authorized\":false,\"authorization_code\":\"\"}")));

        mvc.perform(post("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248872","expiry_month":4,"expiry_year":2030,
                     "currency":"USD","amount":50,"cvv":"321"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("Declined"));
    }

    @Test
    void processPayment_bankReturns503_returns502() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/payments"))
            .willReturn(serviceUnavailable()));

        mvc.perform(post("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248870","expiry_month":4,"expiry_year":2030,
                     "currency":"GBP","amount":100,"cvv":"123"}
                    """))
            .andExpect(status().isBadGateway());
    }

    @Test
    void processPayment_nullCardNumber_returns422_noBankCall() throws Exception {
        mvc.perform(post("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expiry_month":4,"expiry_year":2030,"currency":"GBP","amount":100,"cvv":"123"}
                    """))
            .andExpect(status().isUnprocessableEntity());

        wireMock.verify(0, postRequestedFor(urlEqualTo("/payments")));
    }

    @Test
    void processPayment_unsupportedCurrency_returns422() throws Exception {
        mvc.perform(post("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248877","expiry_month":4,"expiry_year":2030,
                     "currency":"CAD","amount":100,"cvv":"123"}
                    """))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void processPayment_expiredCard_returns422() throws Exception {
        mvc.perform(post("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248877","expiry_month":1,"expiry_year":2020,
                     "currency":"GBP","amount":100,"cvv":"123"}
                    """))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getPayment_afterSuccessfulPost_returns200WithSameData() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/payments"))
            .willReturn(okJson("{\"authorized\":true,\"authorization_code\":\"abc-123\"}")));

        String postResponse = mvc.perform(post("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"card_number":"2222405343248877","expiry_month":4,"expiry_year":2030,
                     "currency":"EUR","amount":200,"cvv":"999"}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String id = postResponse.split("\"id\":\"")[1].split("\"")[0];

        mvc.perform(get("/payments/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.status").value("Authorized"))
            .andExpect(jsonPath("$.currency").value("EUR"));
    }
}
```

---

## Step 16: Update `README.md`

Replace the template content with sections covering:
1. **How to run** — `docker-compose up -d` + `./gradlew bootRun`; links to Swagger and Actuator health
2. **Architecture** — 4-layer flow: Controller → Service → BankClient + Repository
3. **Design decisions** — Bean Validation + custom `@ValidExpiryDate` + currency allowlist; 422 vs 400; 201 vs 200; separate `BankPaymentRequest` DTO; deleted `GetPaymentResponse`; CVV as String; Lombok; no idempotency (with rationale)
4. **Assumptions** — currencies (USD/GBP/EUR), in-memory storage, no retry on bank 503
5. **Observability** — Actuator endpoints (`/actuator/health`, `/actuator/prometheus`); production JSON logging with Logback + `logstash-logback-encoder`; MDC correlation IDs; PCI logging policy (last-four only); Micrometer counters and timers; Prometheus + Grafana stack via docker-compose
6. **Testing strategy** — three tiers: pure unit (validator, expiry constraint), Mockito (service), WireMock (integration)

---

## Files Created / Modified

| File | Action |
|------|--------|
| `build.gradle` | Add actuator, validation, Lombok, prometheus, logstash-logback-encoder, wiremock |
| `src/main/resources/application.properties` | Bank URL, Actuator, Prometheus, log pattern |
| `src/main/resources/logback-spring.xml` | **Create** JSON logging for `production` profile |
| `prometheus.yml` | **Create** Prometheus scrape config |
| `docker-compose.yml` | Add Prometheus + Grafana services |
| `.../model/PostPaymentRequest.java` | Fix card number bug, Lombok @Data, Bean Validation annotations |
| `.../model/PostPaymentResponse.java` | Lombok @Data, @JsonProperty snake_case |
| `.../model/GetPaymentResponse.java` | **Delete** (dead duplicate) |
| `.../client/BankPaymentRequest.java` | **Create** with Lombok @Builder |
| `.../client/BankPaymentResponse.java` | **Create** with Lombok @Data |
| `.../client/BankClient.java` | **Create** HTTP client, MDC logging |
| `.../validation/ValidExpiryDate.java` | **Create** custom constraint annotation |
| `.../validation/ExpiryDateValidator.java` | **Create** compound expiry constraint impl |
| `.../validation/PaymentRequestValidator.java` | **Create** currency allowlist check |
| `.../filter/RequestCorrelationFilter.java` | **Create** MDC correlation ID filter |
| `.../exception/PaymentValidationException.java` | **Create** |
| `.../exception/BankCommunicationException.java` | **Create** |
| `.../exception/CommonExceptionHandler.java` | Add MethodArgumentNotValidException, PaymentValidationException, BankCommunicationException handlers |
| `.../service/PaymentGatewayService.java` | Implement processPayment, inject MeterRegistry, MDC |
| `.../controller/PaymentGatewayController.java` | Add POST with @Valid, fix paths, OpenAPI annotations |
| `.../controller/PaymentGatewayControllerTest.java` | Fix paths, JSON key assertions |
| `.../validation/PaymentRequestValidatorTest.java` | **Create** currency allowlist unit tests |
| `.../validation/ExpiryDateValidatorTest.java` | **Create** expiry constraint boundary tests |
| `.../service/PaymentGatewayServiceTest.java` | **Create** Mockito unit tests with metrics |
| `.../controller/ProcessPaymentControllerTest.java` | **Create** WireMock integration tests |
| `README.md` | Rewrite with technical documentation |

---

## Verification

```bash
# Run all tests
./gradlew test

# Start bank simulator + Prometheus + Grafana
docker-compose up -d

# Start gateway (dev — pattern logging)
./gradlew bootRun

# Start gateway (production — JSON logging)
./gradlew bootRun --args='--spring.profiles.active=production'

# Authorized payment (card ends in 7)
curl -s -X POST http://localhost:8090/payments \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: test-001" \
  -d '{"card_number":"2222405343248877","expiry_month":4,"expiry_year":2030,"currency":"GBP","amount":100,"cvv":"123"}' | jq .

# Retrieve that payment (use id from response)
curl -s http://localhost:8090/payments/{id} | jq .

# Declined payment (card ends in 2)
curl -s -X POST http://localhost:8090/payments \
  -H "Content-Type: application/json" \
  -d '{"card_number":"2222405343248872","expiry_month":4,"expiry_year":2030,"currency":"USD","amount":50,"cvv":"321"}' | jq .

# Rejected (invalid currency)
curl -s -X POST http://localhost:8090/payments \
  -H "Content-Type: application/json" \
  -d '{"card_number":"2222405343248877","expiry_month":4,"expiry_year":2030,"currency":"CAD","amount":100,"cvv":"123"}' | jq .

# Swagger UI
open http://localhost:8090/swagger-ui/index.html

# Actuator health + Prometheus metrics
curl http://localhost:8090/actuator/health | jq .
curl http://localhost:8090/actuator/prometheus | grep payments

# Grafana dashboard
open http://localhost:3000   # admin/admin — add Prometheus datasource: http://prometheus:9090
```
