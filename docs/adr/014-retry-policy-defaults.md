# ADR-014 — Retry policy defaults

- **Status**: Accepted (amended 2026-08-28 and 2026-08-29 — see [Amendments](#amendments))
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
- **`Retry-After` header**: respected when the server sends it; overrides the computed backoff.
  *Amended 2026-08-28 — honoured up to `maxDelay`, see [Amendments](#amendments) and
  [ADR-025](025-retry-after-handling.md).*

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
permanent condition, not a transient one. The typed `ErrorCode` lets us distinguish. Both carry the server's
`Retry-After` hint for caller-side scheduling (ADR-025).

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
semantic implication of replaying partially-consumed events. *(Wording corrected 2026-08-29, see
[Amendments](#amendments).)*

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

## Amendments

### 2026-08-28 — `Retry-After` ceiling, predicate bounds, and the retryable set made real (0.3.0)

Through 0.2.0 the retryable set above was unimplemented end-to-end for every HTTP-status row: the
domain facades mapped 4xx/5xx to typed exceptions only after the interceptor chain had returned,
so `RetryInterceptor` retried transport failures and nothing else. 0.3.0 moves the mapping to the
retry boundary inside the chain (ADR-012 amendment); the table now describes what happens.

In the same release [ADR-025](025-retry-after-handling.md) amends the `Retry-After` clause:
hints are honoured up to `maxDelay`, a larger hint ends retrying with the exception surfacing
hint-preserved, non-positive / past-date / unparseable hints count as absent, and both HTTP 429
subtypes carry the hint (`exceeded_quota` stays non-retryable). The "full control" wording in the
trade-offs above is narrowed accordingly — the predicate decides *which*, the policy's bounds
decide *how long* — and the worst-case latency arithmetic, which never matched the 500 ms base,
is corrected in place.

### 2026-08-29 — streaming posture: wording corrected and proved (0.4.0)

The streaming clause said a mid-stream disconnect surfaces as an `ErrorChunk`. It never did: `ErrorChunk` is the
decoded shape of a server-sent error *frame*, while a transport failure after the handshake reaches the subscriber
as `onError` (the response body's `IOException`, unwrapped). The wording above is corrected in place; the posture —
retry the handshake only, never re-request mid-stream — is unchanged and now proved by the seam-crossing tests
named in each section. This is the 0.4.0 rule: an ADR names the `*IntegrationTest` that proves what it promises
(CONTRIBUTING → Testing).

### 2026-08-29 — a total sleep budget and a builder (0.4.0, ADR-027)

The policy bounded each sleep and the number of attempts but not their sum. ADR-027 adds
`maxTotalDelay` (default 1 min — the worst case the other defaults already allowed, so nothing
changes at the defaults): a sleep that would push one call's cumulative sleep over the budget is
never started, retrying ends and the exception surfaces with its hint preserved, mirroring the
ADR-025 ceiling. The customization API gains `RetryPolicy.builder()` (from `defaults()`) and
`withMaxTotalDelay`; the canonical constructor's arity changes — a pre-1.0 break under ADR-019,
recorded in the changelog. The bounds that end retrying regardless of `retryable` are therefore
three: `maxAttempts`, `maxDelay`, `maxTotalDelay`.

## References

- ADR-006 Unchecked exception hierarchy (typed `ErrorCode` mapping)
- ADR-012 Interceptor SPI
- ADR-013 Observability SPI
- ADR-016 `FanarClient` builder and domain facades
- ADR-025 Retry-After handling (amends the `Retry-After` clause above)
- "Exponential Backoff and Jitter", AWS Architecture Blog
- Google SRE Book, "Handling overload"
