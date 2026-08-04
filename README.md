# Payment Gateway

A Spring Boot payment gateway that processes card payments through an acquiring bank simulator.

## Requirements

- JDK 21 (Gradle's toolchain support will provision it automatically if not already installed)
- Docker (for the bank simulator and optional observability stack)

## How to Run

```bash
# Start bank simulator (required) + optional Prometheus + Grafana
docker-compose up -d

# Run the gateway (port 8090)
./gradlew bootRun

# Run with production JSON logging
./gradlew bootRun --args='--spring.profiles.active=production'
```

| Endpoint | URL |
|----------|-----|
| Swagger UI | http://localhost:8090/swagger-ui/index.html |
| Health | http://localhost:8090/actuator/health |
| Prometheus metrics | http://localhost:8090/actuator/prometheus |
| Grafana | http://localhost:3000 (admin/admin) |

## API

### POST /api/v1/payments — Process a payment

```json
{
  "card_number": "2222405343248877",
  "expiry_month": 4,
  "expiry_year": 2030,
  "currency": "GBP",
  "amount": 100,
  "cvv": "123"
}
```

Optionally send an `Idempotency-Key` header (1-255 characters, letters/digits/`-`/`_`) to safely retry a request without double-charging. A repeat request with the same key and the same payload returns the original response with an `Idempotent-Replayed: true` header instead of calling the bank again; the same key reused with a *different* payload is rejected with `409 Conflict`.

**Responses:**
- `201 Created` — payment processed; body contains `status: Authorized` or `status: Declined`
- `200 OK` — idempotent replay of a previous response (`Idempotent-Replayed: true` header set)
- `422 Unprocessable Entity` — payment rejected (invalid input; bank was not called)
- `409 Conflict` — idempotency key reused with a different payload
- `502 Bad Gateway` — acquiring bank unavailable (after retries/circuit-breaker are exhausted)

### GET /api/v1/payments/{id} — Retrieve a payment

- `200 OK` — payment found
- `404 Not Found` — unknown ID

## Architecture

```
Controller  →  PaymentGatewayService  →  ResilientBankClient (retry + circuit breaker)  →  HttpBankClient
                        │        │
                        │        └→ IIdempotencyStore (in-memory)
                 PaymentsRepository (in-memory)
```

**Validation layer:**
- Jakarta Bean Validation (`@Valid`) on the request DTO handles field-level constraints (card number regex, month range, CVV pattern, positive amount)
- `@ValidExpiryDate` custom constraint checks the compound month+year future rule
- `@ValidCurrency` (backed by `PaymentRequestValidator`) enforces the currency allowlist (USD, GBP, EUR) as a class-level constraint

**Resilience layer:**
- `ResilientBankClient` wraps the HTTP bank call with a Resilience4j `Retry` (exponential backoff, configurable max attempts) and `CircuitBreaker` (opens on a sustained failure rate, fast-fails while open)
- Configurable via `gateway.acquiring-bank.resilience.*` properties (see `BankProperties`); sensible defaults are baked in if unset
- Exhausted retries / an open circuit / a bank communication failure all surface as `502 Bad Gateway`

**Idempotency layer:**
- `InMemoryIdempotencyStore` atomically associates an `Idempotency-Key` with a request fingerprint (SHA-256 of the payment fields) and the resulting response
- A replayed key with a matching fingerprint short-circuits the bank call and returns the stored response; a mismatched fingerprint is rejected as a key-reuse conflict

**Observability layer:**
- `RequestCorrelationFilter` attaches a `correlationId` to every request via MDC; echoes it in the `X-Correlation-ID` response header
- `MessageLoggingFilter` logs masked inbound request/response bodies (via `SensitiveDataMasker`, which redacts `cvv` and partially masks `card_number`)
- Micrometer counters (`payments.processed` tagged by `status`/`currency`) are scraped by Prometheus

## Design Decisions

**Why 201 for Authorized/Declined, not 200:** A payment resource is created and stored in both outcomes. The HTTP status reflects the gateway result (resource created), not the bank result (which is in the `status` field).

**Why 422 for rejected payments:** The resource was never created; `422 Unprocessable Entity` is more precise than `400 Bad Request` for domain-level validation failures. Importantly, the bank is never called, so no charge is attempted.

**Why Bean Validation + a manual validator:** Bean Validation annotations handle simple structural checks cheaply at the controller boundary. The compound expiry rule (month+year together) is expressed as a custom `@ValidExpiryDate` class-level constraint. The currency allowlist lives in `PaymentRequestValidator` for independent unit testability without a Spring context.

**Why a separate `BankPaymentRequest` DTO:** The bank API shape (`expiry_date` as `"04/2025"`, CVV as string) differs from what the merchant sends. Keeping them separate means the bank contract can evolve without breaking the merchant API.

**Why `GetPaymentResponse` was deleted:** It was an exact copy of `PostPaymentResponse` with no functional difference. Keeping two identical classes creates maintenance burden with no benefit.

**`cvv` and `card_number_last_four` stored/returned as `String`:** An integer would silently drop leading zeros (e.g. CVV `007` → `7`, or a card ending `0004` → `4`). Both are kept as strings so the exact digits are preserved end to end.

**Idempotency via header, not implicit dedup:** Retry-safety is opt-in (`Idempotency-Key` header) rather than automatic, matching how Stripe/Adyen-style payment APIs behave. Fingerprinting the payload (rather than trusting the key alone) means a key reused for a genuinely different payment is caught as a conflict instead of silently returning the wrong stored response.

**Retry + circuit breaker around the bank call:** The bank simulator's `503` is treated as a transient failure worth retrying (bounded, exponential backoff) rather than failing immediately; a circuit breaker prevents hammering a bank that's persistently down. Both are scoped to the bank client only — gateway-side validation failures never reach this layer.

**PCI logging policy:** Full card numbers and CVVs are never logged at any level. `SensitiveDataMasker` redacts `cvv` entirely and partially masks `card_number` (keeps last four) in the request/response bodies captured by `MessageLoggingFilter`.

## Assumptions

- **Supported currencies:** USD, GBP, EUR (three, as the spec requires)
- **Storage:** In-memory (`ConcurrentHashMap`) for both payments and idempotency records. Restarting the service clears all state.
- **Idempotency store is not durable:** It's process-local and unbounded (no TTL/eviction). Fine for this exercise; production would need a durable, TTL'd store (Redis/DB) with atomic read-then-write semantics.
- **Bank 503 / timeouts:** Retried with exponential backoff and protected by a circuit breaker (see `gateway.acquiring-bank.resilience.*` in `BankProperties`); once retries are exhausted or the circuit is open, the gateway responds `502 Bad Gateway`.

## Observability

**Metrics (Prometheus → Grafana):**

The `/actuator/prometheus` endpoint exposes standard Spring Boot/JVM metrics plus:

| Metric | Description |
|--------|-------------|
| `payments_processed_total{status,currency}` | Payments processed per bank outcome (`authorized`/`declined`) and currency |

**Grafana Dashboards:**
Grafana has three dashboards set up: Message Logs, Application Logs, and Metrics.
Message logs show requests into our system and calls out to external clients.
Application logs are what our own code logs directly.
Metrics come from the metrics endpoint, for monitoring system performance and business benchmarks.

![Grafana Message Logs](<./files/Screenshot 2026-08-04 at 08.55.14.png>)

Sensitive fields in logs, like CVV and card number, are masked partially or entirely, based on configuration.
![CVV and card number masked in a message log entry](<./files/Screenshot 2026-08-04 at 08.55.45.png>)
![Card number partially masked in a message log entry](<./files/Screenshot 2026-08-04 at 08.56.00.png>)

Metrics are stored and visualized for resource monitoring and analysis.
![Grafana metrics dashboard](<./files/Screenshot 2026-08-04 at 08.57.15.png>)

Message logs are emitted from application code and captured on the exception handler. The relevant fields are logged, and the request/correlation ID lets you see all logs associated with a single request.
![Application logs for a single request](<./files/Screenshot 2026-08-04 at 08.57.32.png>)
![Message logs correlated by request ID](<./files/Screenshot 2026-08-04 at 08.58.55.png>)

**Logging:**

- Dev profile: timestamped console pattern including the `correlationId` MDC field (set per-request by `RequestCorrelationFilter`, echoed back in the `X-Correlation-ID` response header)
- Production profile (`--spring.profiles.active=production`): structured JSON via `logstash-logback-encoder`, consumable by Grafana Loki/Promtail
- `MessageLoggingFilter` + `MessageLogger` additionally emit a structured `MESSAGE_LOG` entry for every inbound HTTP call (method, path, status, duration, and masked request/response bodies) — this is the primary audit trail, independent of business-logic logs in the controller/service/bank-client layers

```
{"@timestamp":"...","correlationId":"a3f1-...","level":"INFO","logger_name":"MESSAGE_LOG","event_type":"MSG_LOG","msg_direction":"INBOUND","msg_method":"POST","msg_endpoint":"/api/v1/payments","msg_status":"201","msg_duration_ms":"42","app":"payment-gateway"}
```

## Testing

| Layer | Example classes | Scope |
|-------|------------------|-------|
| Pure unit | `ExpiryDateValidatorTest`, `PaymentRequestValidatorTest`, `FingerprintServiceTest`, `SensitiveDataMaskerTest` | Validation rules, fingerprinting, log masking |
| Unit | `InMemoryIdempotencyStoreTest`, `PaymentMapperTest`, `BankPaymentMapperTest`, `CommonExceptionHandlerTest` | Idempotency store semantics (fresh/replay/conflict), DTO mapping, error handling |
| Mockito unit | `PaymentGatewayServiceTest`, `RetryingBankClientTest`, `HttpBankClientTest` | Service orchestration, retry/circuit-breaker behaviour with mocked HTTP |
| MockMvc / WireMock integration | `ProcessPaymentControllerTest`, `PaymentGatewayControllerTest` | Full HTTP flow — validation, idempotency headers, bank call, retrieval, 4xx/5xx paths |
| Filter | `RequestCorrelationFilterTest`, `MessageLoggingFilterTest` | Correlation ID propagation, masked message logging |

```bash
./gradlew test               # runs the full suite, generates a JaCoCo report under build/reports/jacoco
```
