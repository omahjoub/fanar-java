# ADR-025 — Retry-After handling: ceiling, normalisation, and the quota hint

- **Status**: Accepted
- **Date**: 2026-08-28
- **Deciders**: @omahjoub

## Context

ADR-014 promised that a server `Retry-After` hint is "respected when the server sends it" and
that 429 / 500 / 503 / 504 are retried. Through 0.2.0 neither was true end-to-end: every domain
facade turned an error response into a typed exception only *after* the interceptor chain had
returned, so `RetryInterceptor` — which decides on typed exceptions — never saw an HTTP-status
error at all. Only transport failures (`FanarTransportException`) were ever retried. The unit
tests injected the expected exceptions straight into the loop and every facade test used
`RetryPolicy.disabled()`, so nothing crossed the seam. The 2026-08-28 review found it; the fix
(mapping at the retry boundary, inside the chain) is recorded as an amendment to ADR-012 and
ADR-006. This record defines what the loop does with the hint now that it actually receives one.

The 2026-08-27 Fanar spec refresh documents the rate-limit response-header contract —
`x-ratelimit-limit` / `x-ratelimit-remaining` / `x-ratelimit-reset`, `ratelimit-policy` as
`limit;w=seconds`, and `retry-after` (429-only, in seconds) — and with it three facts that shape
the decision:

1. **Quota windows reach a day.** The model table has per-minute (`w=60`) and per-day
   (`w=86400`) budgets; the spec's own example is `100;w=86400`. A `retry-after` that "counts
   down to a free slot" on an exhausted 20/day model can legitimately be hours.
2. **Both 429 codes can carry it.** The spec's 429 example on every operation is
   `exceeded_quota`; `rate_limit_reached` shares the status. Whichever code Fanar sends for an
   exhausted window, the countdown is the information a caller needs — and the one exhausted
   window observed so far came back as `rate_limit_reached`, i.e. through the *retryable*
   subtype, which is exactly where an unbounded hint does damage.
3. **The value is not always Fanar's own countdown.** When an upstream service throttled the
   request, `retry-after` relays that service's hint. RFC 9110 also permits an HTTP-date form,
   and nothing stops a relay from sending zero or a negative number.

Live verification (2026-08-27/28, standard key) confirmed the headers on the wire: chat 2xx
responses carry `50;w=60` (`LiveRateLimitHeadersTest` pins the contract), and on 2026-08-28 an
exhausted per-day window was observed for real — `Fanar-Aura-TTS-2` (`20;w=86400`,
`x-ratelimit-remaining: 0`) answered 429 with envelope code `rate_limit_reached` (not
`exceeded_quota`), `retry-after: 28606` equal to `x-ratelimit-reset`: a 7.9-hour countdown. The
interceptor built here surfaced it in 496 ms with `fanar.retry_count=0`; the pre-0.3.0 loop, had
it ever received the response, would have slept those eight hours on the caller's thread.

Sleeping a caller's thread for hours inside a client library, invisibly, because a header said so,
is a defect — the default policy advertises `maxDelay` as its longest wait, and an unbounded hint
would void that contract. Sync-primary (ADR-004) makes it worse: the blocked thread is the user's.

## Decision

1. **`RetryPolicy.maxDelay()` is the ceiling on honoured hints.** A hint ≤ `maxDelay` replaces the
   computed backoff for that sleep (inclusive: `hint == maxDelay` sleeps). A hint above it ends
   retrying immediately — no sleep, no burned attempt, no `retry_attempt` event — and the
   exception surfaces with the hint preserved so the caller can schedule around it (enqueue,
   defer, fail over).
2. **Both 429 subtypes carry the hint.** `FanarRateLimitException.retryAfter()` and the new
   `FanarQuotaExceededException.retryAfter()` expose it (nullable). Quota exhaustion stays
   non-retryable under the default predicate (ADR-014); a caller who opts in via `retryable` gets
   the same ceiling semantics.
3. **The hint is normalised at the mapper.** `delay-seconds` ≤ 0, an HTTP-date already past, or an
   unparseable value carry no scheduling information and are treated as *absent* — the loop falls
   back to computed backoff instead of re-requesting immediately. A future HTTP-date becomes the
   remaining wait.
4. **The ceiling applies regardless of `retryable`.** The predicate decides *which* exceptions are
   worth retrying; the attempt budget and the delay ceiling are the policy's other bounds and end
   retrying on their own. This is documented on `RetryPolicy`, not hidden in the loop.
5. **The default ceiling stays at 30 s.** ADR-014's defaults are interactive-leaning (it rejected
   five attempts on tail latency). On the dominant `50;w=60` chat window a countdown can be
   anywhere in (0, 60] s; with a 30 s ceiling the upper half surfaces immediately, hint attached,
   instead of blocking a chat UI for most of a minute. Callers who would rather bridge a
   per-minute window raise `maxDelay` to 60 s — one knob in core, `fanar.retry.max-delay` in the
   Spring starter — and the computed curve is unaffected at the default attempt count.
6. **Telemetry.** `fanar.retry_count` is recorded on every exit of the loop, `0` included, so a
   first-attempt abort is observable; `http.status_code` is recorded per attempt (last wins). No
   event fires on the abort because no retry is attempted.

No new `RetryPolicy` component is introduced — the existing knob already expresses "the longest
this policy is willing to wait between attempts".

This **amends the `Retry-After` clause of ADR-014**. Everything else in ADR-014 — retryable set,
backoff, jitter, streaming posture — is untouched.

## Alternatives considered

- **Clamp the hint to `maxDelay` and retry anyway.** *Rejected*: the server just priced the wait;
  retrying sooner is a retry we already know will 429. It burns an attempt, adds a pointless
  request against a throttled endpoint, and turns "respect the server" into "contradict the
  server, slowly".
- **Sleep the full hint.** *Rejected*: holds the calling thread hostage for up to a day with no
  signal to the caller, and makes `maxDelay` a lie whenever the server sends a hint.
- **A dedicated `maxRetryAfter` policy component.** *Rejected*: a second duration knob whose
  interaction with `maxDelay` needs explaining, plus a breaking change to the `RetryPolicy`
  canonical constructor for zero expressive gain.
- **Route long hints to a caller-supplied callback.** *Rejected*: a new SPI surface for a policy
  edge case. The sealed exception with `retryAfter()` populated *is* the callback.
- **Raise the default `maxDelay` to 60 s.** *Rejected for now*: it would bridge every per-minute
  countdown but doubles the worst-case hinted wait to two minutes across the default three
  attempts, against ADR-014's interactive bias. Revisit with observed 429 data.
- **Treat `Retry-After: 0` as "retry now".** *Rejected*: RFC-legal but, combined with a retry
  budget, it turns the loop into an immediate re-request storm against an endpoint that just
  throttled the caller. Computed backoff is the safer reading of "no useful wait".

## Consequences

### Positive
- `maxDelay` is an honest, single upper bound on every inter-attempt wait — computed or hinted.
- Callers facing a long wait get the exception plus the machine-readable hint immediately —
  for quota exhaustion too, which is where the long waits actually live.
- Malformed or hostile hints cannot drive the loop into zero-pacing retries.

### Negative / Trade-offs
- On the `50;w=60` chat window, hints in (30 s, 60 s] surface immediately under the default
  policy where a 60 s ceiling would have bridged them. The remedy is one knob; the failure
  carries the hint.
- `FanarQuotaExceededException` gains a constructor and accessor — additive (ADR-019).
- `RetryPolicy` now rejects a `maxDelay` that is not representable in milliseconds
  (≈ 292 million years); sentinel "forever" values fail at construction instead of throwing an
  `ArithmeticException` at retry time.

### Neutral
- The behaviour is visible only now that HTTP-status retry fires at all (ADR-012 amendment):
  callers see retries and sleeps on 429 / 5xx that never happened before 0.3.0. Recorded in the
  changelog as the behaviour change it is.

## References

- ADR-004 Sync-primary API with async sugar (why a blocked thread is the caller's thread)
- ADR-006 Unchecked exception hierarchy (`retryAfter()` on both 429 subtypes; amended 2026-08-28)
- ADR-012 Interceptor SPI (amended 2026-08-28: error mapping at the retry boundary)
- ADR-014 Retry policy defaults (amended by this record)
- ADR-018 Internals are not a contract
- ADR-020 Spring Boot 4 starter shape (amended 2026-08-28: `fanar.retry.max-delay`, `RetryPolicy` bean)
- Fanar OpenAPI `info.description`, 2026-08-27 refresh — rate-limit header contract
- RFC 9110 §10.2.3 — `Retry-After`
- `LiveRateLimitHeadersTest` — live-observed header shapes, dated caveats
