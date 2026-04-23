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
- **First-added interceptor = outermost wrapper**. Registration order determines chain order. Auth registered first
  means it wraps retry means it wraps logging — so retries re-run the auth-wrapped path (fresh tokens on each attempt),
  and each attempt is individually logged.
- **Exceptions are unchecked** per ADR-006. `Chain.proceed` wraps any `IOException` / `InterruptedException` from the
  transport into a `FanarTransportException` so interceptors never have to declare checked exceptions.
- **`Chain.observation()`** exposes the current observation handle (ADR-013) so interceptors can attach events
  (retry attempts, cache hits) without smuggling context through `ThreadLocal` or the preview `ScopedValue`.

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
- **Ship logging and rate-limit interceptors in core**. *Rejected*: logging belongs to observability (ADR-013);
  rate-limiting is application-specific (per-tenant, per-IP, etc.) and should be user-supplied.

## Consequences

### Positive
- Pattern matches JVM muscle memory (OkHttp, Retrofit) — users recognize and compose naturally.
- Functional interface is lambda-friendly: trivial interceptors are one-liners.
- `Chain`-based re-entry makes control flow explicit (before / around / after).
- Order is explicit (first-added outermost), no magical priority annotations.
- Two built-ins (auth + retry) cover what every caller needs; nothing else is imposed.

### Negative / Trade-offs
- `Chain.observation()` creates a small coupling between Q12 and Q13 SPIs. The alternative (ThreadLocal context or
  preview `ScopedValue`) is either unsafe with virtual-thread migrations or violates JLBP-4 (no preview features) —
  the coupling is the lesser evil.
- Users wanting to transform semantic types (e.g., inject additional messages into every `ChatRequest`) must operate
  at the call site or via a wrapping facade; no interceptor sees the DTO directly. Acceptable for v1.

### Neutral
- Every interceptor must be thread-safe — executed potentially from multiple threads concurrently against the same
  `FanarClient` instance.

## References

- ADR-003 Framework-agnostic public API
- ADR-004 Sync-primary API with async sugar
- ADR-006 Unchecked exception hierarchy
- ADR-013 Observability SPI
- ADR-014 Retry policy defaults
- ADR-018 Internals are not a contract
