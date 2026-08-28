# ADR-025 — Retry-After ceiling: hints above `maxDelay` abort the retry loop

- **Status**: Proposed
- **Date**: 2026-08-27
- **Deciders**: @omahjoub

## Context

ADR-014 made `Retry-After` "always respected when the server sends it; overrides the computed
backoff". The implementation was faithful to the letter: `RetryInterceptor` slept for whatever
duration the server hinted, bypassing `RetryPolicy.maxDelay()` entirely. With every observed
hint being a few seconds, the gap was theoretical.

The 2026-08-27 Fanar spec refresh made it concrete. It documents the rate-limit response-header
contract — `x-ratelimit-limit` / `x-ratelimit-remaining` / `x-ratelimit-reset`,
`ratelimit-policy` as `limit;w=seconds`, and `retry-after` (429-only, in seconds) — and with it
two facts that break the "always respect" posture:

1. **Quota windows reach a day.** The published model table has per-minute (`w=60`) and
   per-day (`w=86400`) budgets; the spec's own example is `100;w=86400`. A `retry-after` that
   "counts down to a free slot" on an exhausted 20/day model can legitimately be hours.
2. **The hint isn't always Fanar's own countdown.** When an upstream service throttled the
   request, `retry-after` relays that service's hint — a value we control even less.

Live verification (2026-08-27, standard key) confirmed the headers on the wire: chat 2xx
responses carry `50;w=60`, and `x-ratelimit-reset` is not a precise countdown at low
utilization. `LiveChatCompletionsTest` §7 pins the contract.

Sleeping a caller's (possibly virtual, but from the caller's view: blocked) thread for hours
inside a client library, invisibly, because a header said so, is a defect — the default policy
advertises ~30 s worst-case pacing (`maxDelay`), and the hint path silently voided that
contract.

## Decision

`RetryPolicy.maxDelay()` becomes the **ceiling on honoured `Retry-After` hints**:

- **Hint ≤ `maxDelay`** — honoured exactly as before: the hint replaces the computed backoff
  for that sleep.
- **Hint > `maxDelay`** — the retry loop **gives up immediately**: no sleep, no burned
  attempt, no `retry_attempt` event. The `FanarRateLimitException` surfaces to the caller with
  the hint preserved in `retryAfter()`, so application-level code can schedule around the long
  wait (enqueue, defer, fail over) with full information.

The boundary is inclusive on the honour side (`hint == maxDelay` sleeps). No new `RetryPolicy`
component is introduced — the existing knob already expresses "the longest this policy is
willing to wait between attempts", and adding a record component would break the canonical
constructor for every existing caller.

This **amends the `Retry-After` clause of ADR-014**. Everything else in ADR-014 — retryable
set, backoff, jitter, streaming posture — is untouched.

## Alternatives considered

- **Clamp the hint to `maxDelay` and retry anyway.** *Rejected*: the server just priced the
  wait; retrying sooner is a retry we already know will 429. It burns an attempt from
  `maxAttempts`, adds a pointless request against a throttled endpoint, and turns "respect the
  server" into "contradict the server, slowly".
- **Sleep the full hint (status quo).** *Rejected*: holds the calling thread hostage for up to
  a day with no signal to the caller, and makes `maxDelay` a lie whenever the server sends a
  hint. Sync-primary (ADR-004) makes this worse: the blocked thread is the user's.
- **A dedicated `maxRetryAfter` policy component.** *Rejected*: a second duration knob whose
  interaction with `maxDelay` needs explaining, plus a breaking change to the `RetryPolicy`
  canonical constructor (records — pre-1.0 allows it, but not for zero expressive gain).
  Callers who genuinely want to honour long hints can raise `maxDelay`; callers who want none
  of this supply their own `retryable` predicate or `RetryPolicy.disabled()`.
- **Route long hints to a caller-supplied callback.** *Rejected*: a new SPI surface for a
  policy edge case. The sealed exception with `retryAfter()` populated *is* the callback.

## Consequences

### Positive
- `maxDelay` is now an honest, single upper bound on every inter-attempt wait — computed or
  hinted. The default policy's ~30 s worst-case pacing holds unconditionally.
- Callers facing a long wait get the exception plus the machine-readable hint immediately,
  instead of discovering the wait by observing their thread not returning.
- No public-API change: behavior shift only, inside `internal.retry` (ADR-018).

### Negative / Trade-offs
- Callers who relied on the SDK silently absorbing multi-minute hints now see a
  `FanarRateLimitException` they previously didn't. Pre-1.0 (ADR-019) this is acceptable;
  raising `maxDelay` restores the old behavior explicitly.
- A hint marginally above the ceiling (say 31 s against a 30 s `maxDelay`) fails fast where
  honouring it would have been harmless. The remedy is the same knob, and the failure carries
  the hint.

### Neutral
- The abort path records `fanar.retry.count` like every other exhaustion path; no
  `retry_attempt` event fires because no retry is attempted.

## References

- ADR-004 Sync-primary API with async sugar (why a blocked thread is the caller's thread)
- ADR-006 Unchecked exception hierarchy (`FanarRateLimitException.retryAfter()`)
- ADR-012 Interceptor SPI
- ADR-014 Retry policy defaults (amended by this record)
- ADR-018 Internals are not a contract
- Fanar OpenAPI `info.description`, 2026-08-27 refresh — rate-limit header contract
- `LiveChatCompletionsTest` §7 — live-observed header shapes, dated caveats
