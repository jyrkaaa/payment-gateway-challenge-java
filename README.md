# Payment Gateway

A Spring Boot payment gateway that processes card payments through an acquiring bank simulator.

## Requirements

- JDK 17
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

### POST /payments — Process a payment

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

**Responses:**
- `201 Created` — payment processed; body contains `status: Authorized` or `status: Declined`
- `422 Unprocessable Entity` — payment rejected (invalid input; bank was not called)
- `502 Bad Gateway` — acquiring bank unavailable

### GET /payments/{id} — Retrieve a payment

- `200 OK` — payment found
- `404 Not Found` — unknown ID

## Architecture

```
Controller  →  PaymentGatewayService  →  BankClient (HTTP)
                        │
                 PaymentsRepository (in-memory)
```

**Validation layer:**
- Jakarta Bean Validation (`@Valid`) on the request DTO handles field-level constraints (card number regex, month range, CVV pattern, positive amount)
- `@ValidExpiryDate` custom constraint checks the compound month+year future rule
- `PaymentRequestValidator` enforces the currency allowlist (USD, GBP, EUR)

**Observability layer:**
- `RequestCorrelationFilter` attaches a `correlationId` to every request via MDC; echoes it in the `X-Correlation-ID` response header
- Micrometer counters (`payments.processed`, `payments.rejected`, `payments.bank.errors`) and a `Timer` (`payments.processing.duration`) are scraped by Prometheus

## Design Decisions

**Why 201 for Authorized/Declined, not 200:** A payment resource is created and stored in both outcomes. The HTTP status reflects the gateway result (resource created), not the bank result (which is in the `status` field).

**Why 422 for rejected payments:** The resource was never created; `422 Unprocessable Entity` is more precise than `400 Bad Request` for domain-level validation failures. Importantly, the bank is never called, so no charge is attempted.

**Why Bean Validation + a manual validator:** Bean Validation annotations handle simple structural checks cheaply at the controller boundary. The compound expiry rule (month+year together) is expressed as a custom `@ValidExpiryDate` class-level constraint. The currency allowlist lives in `PaymentRequestValidator` for independent unit testability without a Spring context.

**Why a separate `BankPaymentRequest` DTO:** The bank API shape (`expiry_date` as `"04/2025"`, CVV as string) differs from what the merchant sends. Keeping them separate means the bank contract can evolve without breaking the merchant API.

**Why `GetPaymentResponse` was deleted:** It was an exact copy of `PostPaymentResponse` with no functional difference. Keeping two identical classes creates maintenance burden with no benefit.

**CVV stored as String:** An integer would silently drop leading zeros (e.g. `007` → `7`). String preserves the exact value.

**PCI logging policy:** Full card numbers and CVVs are never logged at any level. Log entries reference only the last four card digits and the payment UUID.

## Assumptions

- **Supported currencies:** USD, GBP, EUR (three, as the spec requires)
- **Storage:** In-memory `HashMap`. Restarting the service clears all payments.
- **Bank 503:** Mapped to `502 Bad Gateway` with no retry. Production deployments would add circuit-breaking (e.g. Resilience4j) and exponential backoff.
- **No idempotency key support:** Merchants should not retry `POST /payments` on timeout. A duplicate request creates a duplicate payment. Production would require an `Idempotency-Key` header backed by a durable (Redis/DB) response store with atomic read-then-write semantics.

## Observability

**Metrics (Prometheus → Grafana):**

The `/actuator/prometheus` endpoint exposes:

| Metric | Description |
|--------|-------------|
| `payments_processed_total{status="authorized"}` | Total authorized payments |
| `payments_processed_total{status="declined"}` | Total declined payments |
| `payments_rejected_total` | Payments rejected at validation |
| `payments_bank_errors_total` | Bank 503 / communication errors |
| `payments_processing_duration_seconds` | End-to-end processing time histogram |

Suggested Grafana queries:
```promql
# Throughput (payments/sec, 5m window)
rate(payments_processed_total{application="payment-gateway"}[5m])

# P95 latency
histogram_quantile(0.95, rate(payments_processing_duration_seconds_bucket[5m]))

# Error rate
rate(payments_bank_errors_total[5m]) + rate(payments_rejected_total[5m])
```

**Logging:**

- Dev profile: timestamped pattern with `correlationId` and `paymentId` MDC fields
- Production profile (`--spring.profiles.active=production`): structured JSON via `logstash-logback-encoder`, consumable by Grafana Loki

```
{"@timestamp":"...","correlationId":"a3f1-...","paymentId":"f9d2-...","level":"INFO","message":"Payment f9d2-... processed with status Authorized for card ending 8877","app":"payment-gateway"}
```

## Testing

Three-tier strategy:

| Layer | Class | Scope |
|-------|-------|-------|
| Pure unit | `ExpiryDateValidatorTest` | Boundary values for expiry compound rule |
| Pure unit | `PaymentRequestValidatorTest` | Currency allowlist |
| Mockito unit | `PaymentGatewayServiceTest` | Service logic with mocked dependencies |
| WireMock integration | `ProcessPaymentControllerTest` | Full HTTP flow through controller, service, bank client |
| MockMvc integration | `PaymentGatewayControllerTest` | GET endpoint and 404 path |

```bash
./gradlew test
```
