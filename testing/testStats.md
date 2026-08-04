# Load Test Investigation — `test.html`

Investigation of why `testing/test.html` (a browser-based load-test tool that fires
concurrent `fetch()` calls at `POST /api/v1/payments`) reported latency/throughput
that looked like the payment gateway was hitting a fixed-capacity resource under load.

## Timeline of findings

### 1. Log feed / live stats not updating (fixed)
`sendRequest()` built each log entry as `{ idempotencyKey, ... }`, but `appendLog()`
read `entry.cid`, which was `undefined`. `entry.cid.slice(0,8)` threw inside the
per-request async loop with no `try/catch`, silently killing that concurrency lane
after its first request — so the log feed and live metrics froze after the first
completion. Fixed by reading `entry.idempotencyKey` in the render code.

### 2. Throughput plateau + rising p95 at concurrency 50 vs 250
Initial two-point comparison:

| Concurrency | Rate (req/s) | p95 |
|---|---|---|
| 50 | 123.5 | 453ms |
| 250 | 233.9 | 1159ms |

Occupancy check (`concurrency / rate`) matched p95 closely at both points
(≈405ms and ≈1069ms), confirming genuine queueing for some fixed-capacity
resource rather than measurement noise.

Hypotheses considered and ruled out one at a time, each against evidence
gathered from the running system:

- **HikariCP / DB connection pool (default 10)** — ruled out immediately: the
  app has no datasource at all. Both `PaymentsRepository` and
  `InMemoryIdempotencyStore` are in-memory `ConcurrentHashMap`s.
- **Retry/backoff-added latency** — ruled out: Resilience4j retry counters
  (`resilience4j_retry_calls_total`) did not rise between runs, so calls were
  succeeding on first attempt, not accumulating 500ms/1000ms backoff delay.
- **Bank simulator (Mountebank) CPU-bound / single-threaded event loop** —
  ruled out: `docker stats` showed ~20% CPU on `bank_simulator` during the
  slow runs, i.e. not compute-saturated.
- **Gateway JVM CPU-bound** — ruled out: `payment_gateway` container also sat
  at ~20% CPU during the same runs. Nothing was compute-bound anywhere.
- **Tomcat default thread pool (max-threads=200)** — tested directly by
  raising `server.tomcat.threads.max` to 400 and re-running the *same*
  concurrency-250 load: result got **worse**, not better (p95 1159ms → 2335ms,
  rate 233.9 → 134.6 req/s). This disproved the Tomcat-pool theory — more
  threads relieving a queue should improve throughput, not degrade it. Also
  ruled out GC/thread-thrash as primary cause since CPU stayed low throughout.
- **Synchronous logging (`MessageLoggingFilter` writing full request/response
  bodies via a non-async Logback `ConsoleAppender`, whose writes are
  internally lock-serialized)** — plausible mechanism (would explain low CPU +
  worse-with-more-threads), trialed by wrapping the appenders in an
  `AsyncAppender`. Result: **no change**. Ruled out.
- **Resilience4j CircuitBreaker sliding-window lock** — confirmed via the
  actual `resilience4j-core` sources: the default
  `SlidingWindowSynchronizationStrategy` is `SYNCHRONIZED`, backed by
  `FixedSizeSlidingWindowMetrics`, which guards every `record()` call (i.e.
  every single request's outcome, success or failure) with one shared
  `ReentrantLock` on the singleton `acquiringBankCircuitBreaker` bean. This
  matched every symptom (low CPU, gets worse with more concurrent threads,
  unaffected by retry counts). Trialed switching to
  `SlidingWindowSynchronizationStrategy.LOCK_FREE`. Result: **no measurable
  change** at the next test — see below for why.

### 3. The real bug: the load-test tool's own render loop
New data point, taken directly from Grafana/Loki server-side timings versus
the browser tool's own numbers:

- Server-measured per-request handling time: ~13–53ms (mostly the outbound
  call to the bank simulator, 10–50ms, plus ~3ms gateway overhead).
- `test.html`-measured p95 at concurrency 5: 82ms — already inflated versus
  the server's own numbers, at a concurrency level far too low to stress any
  server-side pool.

This ruled out every server-side hypothesis above (none of them can explain
an inflated measurement at concurrency 5) and pointed at the tool itself.

Root cause: `renderLive()` was called after **every** request completion and
re-copied + re-sorted the *entire* `durations` array from scratch
(`[...current.durations].sort(...)`), plus rebuilt `statusCodesEl.innerHTML`
and appended a DOM node per request. Since `duration = performance.now() -
start` is captured after `await fetch(...)` resolves, and JS is
single-threaded, a slow synchronous render for one request's completion
delayed the microtask continuation for every other concurrently-resolving
request — inflating their measured duration even though the server had
already responded. Total render work grew ~O(n² log n) with total requests
processed in a run, which is exactly why longer/higher-volume runs looked
progressively worse.

**Fix applied**: decoupled stat recording (cheap, per-request: push to an
array) from rendering (`flushLogFeed()` + `renderLive()`), which now run on a
fixed 100ms interval via `setInterval` instead of once per request. See
`queueLogEntry`/`flushLogFeed`/`RENDER_INTERVAL_MS` in `test.html`.

### 4. Post-fix throughput data — the real signature

With the client-side measurement bug fixed, re-running at fixed count (500)
across a range of concurrency settings:

| Concurrency | Rate (req/s) | p95 | Occupancy (conc/rate) |
|---|---|---|---|
| 10 | 224.8 | 68ms | 44.5ms |
| 20 | 244.2 | 119ms | 81.9ms |
| 50 | 212.4 | 357ms | 235ms |
| 100 | 206.3 | 616ms | 485ms |
| 200 | 244.9 | 855ms | 816ms |

**Throughput is flat (~207–245 req/s) across a 20x range of configured
concurrency, while p95 climbs almost linearly with the concurrency knob**, and
occupancy tracks p95 closely at every level — the same queueing signature as
the very first comparison, but now isolated from the client-side rendering
bug.

Given server-side handling time of ~13–53ms/request (~25–30ms typical), a
throughput ceiling of ~220 req/s implies only about **6 requests are ever
actually in flight at once** (6 ÷ 0.03s ≈ 200 req/s). That matches the
well-known browser platform limit of **6 concurrent connections per origin
for HTTP/1.1** (Chrome, Firefox, and Safari all cap it there; this app serves
plain HTTP/1.1, no TLS/HTTP2, so there is no multiplexing to get around it).

### 5. Confirmation run — after reapplying the LOCK_FREE circuit breaker + async logging fixes

The `LOCK_FREE` sliding-window strategy and the `AsyncAppender` logging change
(§2) were reapplied to `BankConfiguration.java` / `logback-spring.xml` after
being reverted. Rebuilt and re-ran at fixed count (1000) across concurrency
20→300:

| Concurrency | Rate (req/s) | p95 | Occupancy (conc/rate) |
|---|---|---|---|
| 20 | 174.1 | 163ms | 114.9ms |
| 50 | 175.3 | 486ms | 285.2ms |
| 100 | 198.9 | 630ms | 502.8ms |
| 200 | 208.7 | 1064ms | 958.3ms |
| 300 | 236.2 | 1436ms | 1270.5ms |

Same signature as §4: throughput moves only slightly (174 → 236 req/s, ~1.35x)
across a 15x increase in configured concurrency, while p95 climbs roughly
linearly with it, and occupancy tracks p95 at every level. This confirms the
two production-side fixes (which are real improvements and worth keeping) are
not what's gating these numbers — the browser's per-origin connection cap
(§4) remains the dominant constraint on what this tool can measure.

## Conclusion

The payment gateway itself was never the bottleneck. `test.html`'s
"concurrency" setting only controls how many JS loops *request* to send
requests in parallel — actual delivery is capped by the browser's per-origin
connection limit (~6), a hard platform constraint that cannot be changed from
application code, server config, or the `fetch()` API. Everything above ~6
concurrent requests just queues client-side before it reaches the network,
which is why every server-side fix (Tomcat threads, async logging, lock-free
circuit breaker) had no measurable effect on the reported numbers, and why
throughput plateaus at roughly `6 ÷ (avg request latency)` regardless of the
configured concurrency.

Confirm directly: DevTools → Network tab during a concurrency ≥ 20 run shows
most requests spending their time in **Stalled**, not **Waiting (TTFB)**.

To load-test this app at real concurrency beyond ~6, a non-browser tool is
required (e.g. `k6`, `hey`, `wrk`, or a small Node/`ab` script) — none of
which are subject to the browser's per-origin socket cap.

## Bugs fixed in `test.html` during this investigation
- `appendLog` reading `entry.cid` instead of `entry.idempotencyKey`
  (log feed / live stats froze after the first request per lane).
- `renderLive()`/log DOM writes running once per request instead of on a
  throttled interval (inflated latency measurements under concurrent load).
