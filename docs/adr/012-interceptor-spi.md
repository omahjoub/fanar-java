# ADR-012 — Interceptor SPI

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

Cross-cutting concerns — authentication, retry, rate-limiting, logging, caching, custom headers — must be pluggable
and replaceable without forking the SDK. The pattern is well-established in the Java ecosystem: Servlet filters,
OkHttp `Interceptor`, Retrofit, Spring's filter chain, AWS `ExecutionInterceptor`. We must decide on the exact shape
that fits our constraints: functional (ADR-004 sync-primary), framework-agnostic (ADR-003), running on the caller's
thread, composable with virtual threads.

## Decision

The interceptor SPI is a **functional interface** with a `Chain` re-entry pattern:

```java
package qa.fanar.core.spi;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@FunctionalInterface
public interface Interceptor {

    HttpResponse<InputStream> intercept(HttpRequest request, Chain chain);

    interface Chain {
        HttpResponse<InputStream> proceed(HttpRequest request);
        ObservationHandle observation();
    }
}
```

Rules:

- **HTTP-level types**, not semantic DTOs. Every real interceptor concern (auth headers, status-based retry, rate-limit
  sleep, response-body caching) operates at the HTTP layer, not on `ChatRequest`/`ChatResponse`. A semantic-advisor
  layer is not ruled out for the future but is not needed today.
- **Sync-only execution**, running on the caller's thread (ADR-004). The streaming body is out of scope: interceptors
  see the initial HTTP handshake; mid-stream concerns compose at the `Flow.Publisher<StreamEvent>` layer.
- **First-added interceptor = outermost wrapper.** Registration order determines chain order, and
  the order decides what re-runs: everything *inside* retry runs once per attempt, everything
  outside it runs once per call. That is why the built-ins are ordered retry-then-auth and not the
  reverse — with auth outermost the token would be signed once and replayed on every attempt, which
  is exactly wrong for an expiring credential. As shipped, each attempt is re-signed, and a wire
  logger registered after them logs every attempt separately.
- **Exceptions are unchecked** per ADR-006. `Chain.proceed` wraps any `IOException` /
  `InterruptedException` from the transport into a `FanarTransportException`, so interceptors never
  declare checked exceptions. Error *responses* (status ≥ 400) travel back through user interceptors
  as-is and become typed exceptions at the retry boundary — see *Where responses become exceptions*
  below.
- **Exceptions propagate through interceptors unchanged.** An interceptor that wants to observe a
  failure wraps `proceed` in `try`/`catch` or `try`/`finally`, records, and rethrows *the same
  instance* — it never swallows, wraps or substitutes it. `RetryInterceptor` retries a
  `FanarException` the policy accepts; every other `RuntimeException` passes straight through.
- **`Chain.observation()`** exposes the current observation handle (ADR-013) so interceptors can attach events
  (retry attempts, cache hits) without smuggling context through `ThreadLocal` or the preview `ScopedValue`.

### Where responses become exceptions

**At the retry boundary, inside the chain — not in the domain facades after it.** This is the
decision the SPI's shape depends on, and getting it wrong is silent: through 0.2.0 each facade
called the mapper *after* `Chain.proceed` returned, so `RetryInterceptor` — first in the chain,
written to decide on typed exceptions — never saw a 429 or a 5xx. ADR-014's whole retryable set was
dead end-to-end, with full unit coverage over a loop nothing reached. A unit test that hands a unit
the outcome it expects proves the unit, not the wiring.

`RetryInterceptor` therefore maps any response with status ≥ 400 into the `FanarException`
hierarchy (ADR-006) as soon as the rest of the chain returns it, then applies the policy. What that
fixes at the SPI contract:

- **User interceptors still observe raw error responses** — status, headers, body. Logging, capture
  and caching interceptors work on 4xx/5xx exactly as they always did; nothing in `intercept` or
  `Chain` changes.
- **Domain facades only ever see typed exceptions or successful responses.** No post-chain status
  checks; `http.status_code` is recorded at the boundary, per attempt.
- **Still two built-ins.** Mapping is the retry interceptor's job — as in OkHttp, where the retry
  layer classifies raw status itself — not a third built-in.
- A user interceptor that *throws* a `FanarException` is treated like a mapped one: the policy
  decides whether to retry it.

A knock-on the wire logger had to absorb: an interceptor that only logs on the way back logs
nothing when `proceed` throws, so a transport failure used to leave no trace at all. It now logs the
failure and rethrows.

### Built-in interceptors shipped with `fanar-core`

Two, and only two:

- **`BearerTokenInterceptor`** — adds `Authorization: Bearer <token>`. Exposed via the `.apiKey(String)` or
  `.apiKey(Supplier<String>)` builder sugar for token rotation. Users can register it directly if they need custom
  auth logic.
- **`RetryInterceptor`** — configurable attempts, exponential backoff with full jitter, typed retryable-error policy
  (ADR-014).

Logging, metrics, and tracing belong to the observability SPI (ADR-013), not interceptors. Rate-limiting and caching
are user-supplied or downstream-module concerns.

## Alternatives considered

- **Semantic-level interceptors** (`Interceptor<ChatRequest, ChatResponse>`) with a generic or sealed-union call
  type. *Rejected*: every real interceptor concern is HTTP-level; the semantic layer adds complexity with no real
  use case today.
- **Two interceptor layers** (application + network) à la OkHttp. *Rejected*: premature complexity. If a meaningful
  split emerges later, we can add a second SPI additively.
- **Servlet-style `doFilter(request, response, chain)`**. *Rejected*: verbose, treats response as mutable, doesn't
  fit the return-value style of Java HTTP clients.
- **Ship logging and rate-limit interceptors in core**. *Rejected*: rate-limiting is
  application-specific (per-tenant, per-IP) and belongs to the user. Logging is the subtler half —
  *operation-level* logging is observability's job (ADR-013), but *wire-level* logging is not: it
  needs the raw request and response bytes, which only an interceptor sees. That is the
  `fanar-interceptor-logging` module: a separate artifact so core keeps its zero dependencies and
  the SLF4J binding stays opt-in, built on this SPI rather than baked into it.

## Consequences

### Positive
- Pattern matches JVM muscle memory (OkHttp, Retrofit) — users recognize and compose naturally.
- Functional interface is lambda-friendly: trivial interceptors are one-liners.
- `Chain`-based re-entry makes control flow explicit (before / around / after).
- Order is explicit (first-added outermost), no magical priority annotations.
- Two built-ins (auth + retry) cover what every caller needs; nothing else is imposed.

### Negative / Trade-offs
- `Chain.observation()` creates a small coupling between this SPI and the observability SPI (ADR-013). The alternative (ThreadLocal context or
  preview `ScopedValue`) is either unsafe with virtual-thread migrations or violates JLBP-4 (no preview features) —
  the coupling is the lesser evil.
- Users wanting to transform semantic types (e.g., inject additional messages into every `ChatRequest`) must operate
  at the call site or via a wrapping facade; no interceptor sees the DTO directly. Acceptable for v1.

### Neutral
- Every interceptor must be thread-safe — executed potentially from multiple threads concurrently against the same
  `FanarClient` instance.

## Proved by

- `FanarClientRetryIntegrationTest.userInterceptorsSeeRawErrorResponses` — through
  `FanarClient.builder()`: the user interceptor sees the raw 503, the caller sees the decoded
  success.
- `WireLoggingInterceptorIntegrationTest` — a transport failure below the logger is logged on every
  attempt and propagates unchanged; a later interceptor throwing is logged and propagates unchanged.
- `WireLoggingInterceptorTest` — the same contract at unit level across all four levels.

## References

- ADR-003 Framework-agnostic public API
- ADR-004 Sync-primary API with async sugar
- ADR-006 Unchecked exception hierarchy
- ADR-013 Observability SPI
- ADR-014 Retry policy defaults
- ADR-018 Internals are not a contract
- ADR-025 Retry-After handling
