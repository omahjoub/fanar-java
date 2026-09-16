# ADR-014 — Retry policy defaults

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

`RetryInterceptor` ships in `fanar-core` (ADR-012). Its defaults matter disproportionately: most users never tune
retry policy, so the defaults shape the SDK's perceived reliability. The choice interacts with our typed error model
(ADR-006), the Fanar error-code taxonomy, and well-established industry guidance on backoff and jitter.

Retry in a streaming context has special subtleties: re-running an already-consumed stream is dangerous (duplicated
tokens, inconsistent state). Retry of the initial connection is safe; retry mid-stream is not.

## Decision

### Default parameters

- **Attempts**: 3 total (1 initial + 2 retries).
- **Backoff**: exponential with **full jitter**. `delay = random(0, base * 2^(attempt-1))`, where base = 500 ms.
- **Max delay cap**: 30 seconds.
- **Total sleep budget**: 1 min — the worst case the other defaults already allow, so it only
  bites once `maxAttempts` or `maxDelay` is raised (ADR-027).
- **`Retry-After` header**: honoured when the server sends it, up to `maxDelay`. A larger hint ends
  retrying and the exception surfaces with the hint preserved; non-positive, past-date and
  unparseable values count as absent ([ADR-025](025-retry-after-handling.md)).

### Retryable set

Based on typed `ErrorCode` (ADR-006) combined with HTTP status:

| Retryable | Not retryable |
|---|---|
| `rate_limit_reached` (HTTP 429) | `content_filter` (400) |
| `overloaded` (503) | `invalid_authentication` (401) |
| `timeout` (504) | `invalid_authorization` (403) |
| `internal_server_error` (500) — for idempotent operations | `exceeded_quota` (429, permanent) |
| HTTP 408 and 425 — the two 4xx meaning "try again" | `Not found` (404) |
| Any other undeclared 5xx (502, 507, …) | Any other undeclared 4xx (405, 407, 415, …) |
| `IOException` from transport (wrapped as `FanarTransportException`) | `conflict` (409) |
| | `no_longer_supported` (410) |
| | `too_large` (413) |
| | `unprocessable` (422) |

Note that `exceeded_quota` shares HTTP 429 with `rate_limit_reached` but is explicitly non-retryable — quota is a
permanent condition, not a transient one. The typed `ErrorCode` lets us distinguish. Both carry the server's
`Retry-After` hint for caller-side scheduling (ADR-025).

The predicate reads the **branch**, not a list of statuses: `FanarServerException` and
`FanarTransportException` retry, `FanarClientException` does not (ADR-006). That is what keeps the
set honest as codes are added — a new leaf inherits the right answer from where it is filed. Two
statuses are an explicit exception to it, 408 and 425, because they are client-class by number but
mean "try again" rather than "fix your request"; Fanar declares neither, so they reach us only from
an intermediary. Everything else undeclared follows its branch: an unknown 5xx may succeed on
another attempt, an unknown 4xx will not.

*Proved by* `FanarClientRetryIntegrationTest` (core; public builder → interceptor chain → JDK transport → scripted
local server): `retryableErrorResponseIsRetriedThroughThePublicApi` (503 retried),
`exhaustedAttemptsSurfaceTheLastError`, `nonRetryableErrorIsNotRetried` (401),
`exceededQuotaIsNotRetriedButCarriesTheCountdown`, `disabledPolicyStillMapsErrorsButNeverRetries` — and the same
seam entered through the Spring starter (`FanarAutoConfigurationRetryIntegrationTest`) and the Spring AI adapter
(`FanarChatModelRetryIntegrationTest`).

### Streaming retries

Retries apply to the **initial connection handshake only**. A connection that dies mid-stream surfaces as
`onError` on the subscriber of the `Flow.Publisher<StreamEvent>` (ADR-005) — an `ErrorChunk` is a server-*sent*
error frame, not a transport failure — and the user decides whether to re-subscribe, because only they know the
semantic implication of replaying partially-consumed events. The stream's observation records the
failure before that `onError` (ADR-013), so a dropped stream is visible in telemetry even though the
SDK does not retry it.

*Proved by* `FanarClientRetryIntegrationTest.streamingHandshakeIsRetriedThroughThePublicApi` /
`speechStreamHandshakeIsRetriedThroughThePublicApi` (a 503 handshake is retried, then the body streams) and
`connectionDropMidStreamIsNotRetried` / `speechStreamConnectionDropIsNotRetried` (exactly one request, `onError`).

### Customization API

```java
public record RetryPolicy(
    int maxAttempts,
    Duration baseDelay,
    Duration maxDelay,
    double backoffMultiplier,
    JitterStrategy jitter,
    Predicate<FanarException> retryable
) {
    public static RetryPolicy defaults() { /* the values above */ }
    public static RetryPolicy disabled() { /* maxAttempts = 1 */ }

    public RetryPolicy withMaxAttempts(int n)          { /* ... */ }
    public RetryPolicy withBaseDelay(Duration d)       { /* ... */ }
    public RetryPolicy withMaxDelay(Duration d)        { /* ... */ }
    public RetryPolicy withBackoffMultiplier(double m) { /* ... */ }
    public RetryPolicy withJitter(JitterStrategy j)    { /* ... */ }
    public RetryPolicy withRetryable(Predicate<FanarException> p) { /* ... */ }
}

public enum JitterStrategy { NONE, FULL, EQUAL }
```

Exposed via `FanarClient.builder().retryPolicy(RetryPolicy policy)` (ADR-016). `RetryPolicy.disabled()` is the
explicit opt-out.

## Alternatives considered

- **5 attempts by default**. *Rejected*: tail-latency cost in interactive workloads (chat UIs) outweighs the
  marginal reliability gain when the first 3 attempts already absorb transient failures.
- **Linear backoff**. *Rejected*: empirically causes thundering-herd reconvergence after provider outages. The AWS
  and Google architecture literature on this is definitive.
- **No default jitter**. *Rejected*: same reason — deterministic backoff synchronizes retrying clients.
- **Retry `exceeded_quota`**. *Rejected*: quota is a permanent state; retrying wastes caller cycles and potentially
  hits Fanar with requests that will continue to fail.
- **Automatic mid-stream retry**. *Rejected*: cannot know whether the caller's application state can tolerate
  duplicated tokens. Surface the failure, let the user decide.

## Consequences

### Positive
- Safe, well-justified defaults derived from industry consensus (AWS exponential-backoff paper, Google SRE book).
- Typed retryable set: the distinction between `rate_limit_reached` (retryable) and `exceeded_quota` (not) is
  exact, not heuristic.
- `RetryPolicy` record composes well with external configuration systems (a Spring Boot starter or Quarkus config
  can bind to the record directly).
- `.disabled()` is a clear one-word opt-out.

### Negative / Trade-offs
- Users encountering transient failures outside our retryable set must supply a custom `retryable` predicate.
  Mitigated by the `Predicate<FanarException>` hook, which gives full control over *which* exceptions are
  retried; the attempt budget and the `maxDelay` ceiling still apply regardless of its answer (ADR-025).
- With the defaults, computed backoff adds at most ~1.5 s across the two retries (≤ 500 ms + ≤ 1 s with full
  jitter); honoured `Retry-After` hints can add up to `maxDelay` per retry — ~60 s worst case. Callers with
  stricter SLOs tune down.

### Neutral
- The interaction with `Chain.observation()` (ADR-012 / ADR-013) is explicit: `RetryInterceptor` emits
  `retry_attempt` events on the current observation so traces and metrics reflect the retry count.

## References

- ADR-006 Unchecked exception hierarchy (typed `ErrorCode` mapping)
- ADR-012 Interceptor SPI
- ADR-013 Observability SPI
- ADR-016 `FanarClient` builder and domain facades
- ADR-025 Retry-After handling (the ceiling and normalisation rules above)
- ADR-027 `RetryPolicy`: a total sleep budget and a builder
- "Exponential Backoff and Jitter", AWS Architecture Blog
- Google SRE Book, "Handling overload"
