# ADR-027 — `RetryPolicy`: a total sleep budget and a builder

- **Status**: Accepted
- **Date**: 2026-08-29
- **Deciders**: @omahjoub

## Context

`RetryPolicy` bounds each sleep (`maxDelay`, also the `Retry-After` ceiling since ADR-025) and the
number of attempts, but nothing bounds the *sum*. With the defaults the worst case is two sleeps of
30 s; a user who raises `maxDelay` to bridge a full per-minute window (the ADR-025 recipe) and
`maxAttempts` to five can hold the caller's thread for four minutes of sleeping, and every honoured
`Retry-After` hint adds its full length. The SDK is sync-primary (ADR-004): that thread is the
caller's.

`RetryPolicy` is a record whose canonical constructor is public API: every knob added changes its
arity, so adding a budget is a breaking change by construction. The `with*` methods do not help a
first-time construction, and the starter builds its bean positionally too. Pre-1.0 (ADR-019) is
the window in which this costs the least — the library is not yet on Maven Central.

## Decision

1. **`maxTotalDelay`, a new record component.** The budget for the sum of all sleeps within one
   call. Before each sleep the retry loop checks `slept + next ≤ maxTotalDelay`, where `next` is
   the computed back-off (jitter applied) or the honoured `Retry-After` hint; a sleep that would
   exceed the budget is never started — retrying ends and the exception surfaces with the hint
   preserved, exactly like the ADR-025 ceiling. A total that lands exactly on the budget is
   honoured. The exit is observable as every other: `fanar.retry_count` is recorded, no
   `retry_attempt` event is emitted for the refused sleep.
2. **Default 1 minute** — the worst case the other defaults already allowed (two sleeps of at most
   30 s). No behaviour changes at the defaults; the budget bites only once `maxAttempts` or
   `maxDelay` is raised.
3. **Validation**: positive, ≥ `maxDelay` (so at least one maximal sleep fits — a budget below the
   ceiling would make `maxDelay` unreachable), representable in milliseconds. Validated in the
   compact constructor with the other invariants; `with*` and the builder revalidate.
4. **`RetryPolicy.builder()`**, starting from `defaults()`, with one setter per knob and `build()`
   validating through the canonical constructor. `withMaxTotalDelay` joins the `with*` family. The
   builder is the recommended way to construct a policy from now on: knobs added later change the
   canonical constructor's arity, never the builder.
5. **The canonical constructor changes arity** (`maxTotalDelay` after `maxDelay`). **Breaking**
   under ADR-019, called out in the changelog with the migration (use the builder, or insert the
   new argument). No compatibility constructor: keeping the six-argument one would keep the
   positional trap public beside the builder that exists to end it.
6. **Starter**: `fanar.retry.max-total-delay` (default `1m`) on `FanarProperties.Retry`; the
   `fanarRetryPolicy` bean is built through the builder so the four knobs validate together and a
   `max-delay` raised above the budget fails the context at startup instead of silently
   misconfiguring the client (amends ADR-020).

`retryable` stays `Predicate<FanarException>`; an attempt- or elapsed-aware predicate is parked
(plan, out of scope).

## Alternatives considered

- **Defer to the 1.0 API-freeze pass.** *Rejected*: the constructor break only gets more expensive
  with each consumer; the feature is small and its default is invisible.
- **Keep a six-argument compatibility constructor.** *Rejected*: it preserves the positional
  constructor that made every knob a breaking change, next to the builder introduced to end that.
  ADR-019 exists for exactly this kind of break.
- **A wall-clock deadline including request time** (`maxElapsed`). *Rejected*: request time is
  governed by the transport's connect and request timeouts; folding it into the retry budget
  double-counts and makes the guarantee depend on the server's latency rather than the SDK's own
  sleeping.
- **Clamp the last sleep to the remaining budget instead of refusing it.** *Rejected*: it would
  retry a `Retry-After` hint early — the same premature re-request ADR-025 refused.
- **Count the budget across calls (a client-wide token bucket).** *Rejected*: policy the SDK should
  not choose; a user interceptor with the ADR-026 data can implement one.

## Consequences

### Positive
- An upper bound on the time one call spends sleeping, independent of how the other knobs are set
  and of how many hints the server sends.
- A stable construction path (`builder()`) for every future knob.

### Negative / Trade-offs
- The canonical constructor break: positional `new RetryPolicy(...)` calls need a seventh argument
  or a move to the builder. Zero known external consumers at the time of the change.
- One more invariant (`maxTotalDelay ≥ maxDelay`): raising `maxDelay` past 1 min now requires
  raising the budget too — deliberate, and validated loudly at construction (and at Spring startup).

### Neutral
- `RetryPolicy.disabled()` carries the default budget like every other unused knob.
- `equals` / `hashCode` include the new component; `defaults()` stays equal to
  `builder().build()`.

## Proved by

- `FanarClientRetryIntegrationTest.retryAfterHintsBeyondTheTotalBudgetEndRetrying` — two 429s with
  `Retry-After: 1` under a 1 s ceiling and a 1 s budget through the public builder: the first hint
  is slept (exactly the budget), the second is refused, the exception surfaces with the hint, no
  further request is made.
- `RetryInterceptorTest.totalDelayBudgetExactlyReachedIsStillHonoured`,
  `.totalDelayBudgetExceededAbortsBeforeSleeping`,
  `.retryAfterHintBeyondTheRemainingBudgetSurfacesWithTheHintPreserved`.
- `RetryPolicyTest` — validation (`rejects*MaxTotalDelay*`), `withMaxTotalDelayReplacesOnlyThatField`,
  the `builder*` cases (`defaults()` equals `builder().build()`).
- `FanarAutoConfigurationTest` — the knob's default and override, `retryKnobsAreValidatedTogetherAtStartup`.

## References

- ADR-004 Sync-primary API with async sugar (why a sleeping thread is the caller's)
- ADR-014 Retry policy defaults (amended 2026-08-29 by this record)
- ADR-019 Pre-1.0 stability policy (the constructor break)
- ADR-020 Spring Boot 4 starter shape (amended 2026-08-29: `fanar.retry.max-total-delay`)
- ADR-025 Retry-After handling (the per-sleep ceiling this budget complements)
- ADR-026 Rate-limit visibility (the data a client-wide throttle would use instead)
