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
- **`Retry-After` header**: always respected when the server sends it; overrides the computed backoff.

### Retryable set

Based on typed `ErrorCode` (ADR-006) combined with HTTP status:

| Retryable | Not retryable |
|---|---|
| `rate_limit_reached` (HTTP 429) | `content_filter` (400) |
| `overloaded` (503) | `invalid_authentication` (401) |
| `timeout` (504) | `invalid_authorization` (403) |
| `internal_server_error` (500) — for idempotent operations | `exceeded_quota` (429, permanent) |
| HTTP 408, 425, 502 | `Not found` (404) |
| `IOException` from transport (wrapped as `FanarTransportException`) | `conflict` (409) |
| | `no_longer_supported` (410) |
| | `too_large` (413) |
| | `unprocessable` (422) |

Note that `exceeded_quota` shares HTTP 429 with `rate_limit_reached` but is explicitly non-retryable — quota is a
permanent condition, not a transient one. The typed `ErrorCode` lets us distinguish.

### Streaming retries

Retries apply to the **initial connection handshake only**. Mid-stream disconnects surface as an `ErrorChunk` on
the `Flow.Publisher<StreamEvent>` (ADR-005) — the user decides whether to re-subscribe, because only they know the
semantic implication of replaying partially-consumed events.

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
  Mitigated by the `Predicate<FanarException>` hook, which gives full control.
- 3 attempts can extend tail latency to ~30 seconds in the worst case (5 s first retry + 25 s second with cap).
  Callers with stricter SLOs tune down.

### Neutral
- The interaction with `Chain.observation()` (ADR-012 / ADR-013) is explicit: `RetryInterceptor` emits
  `retry_attempt` events on the current observation so traces and metrics reflect the retry count.

## References

- ADR-006 Unchecked exception hierarchy (typed `ErrorCode` mapping)
- ADR-012 Interceptor SPI
- ADR-013 Observability SPI
- ADR-016 `FanarClient` builder and domain facades
- "Exponential Backoff and Jitter", AWS Architecture Blog
- Google SRE Book, "Handling overload"
