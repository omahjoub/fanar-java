# ADR-026 — Rate-limit visibility: observation attributes and `RateLimitInfo` on the 429s

- **Status**: Accepted
- **Date**: 2026-08-29
- **Deciders**: @omahjoub

## Context

The 2026-08-27 Fanar spec documents four rate-limit response headers — `x-ratelimit-limit`,
`x-ratelimit-remaining`, `x-ratelimit-reset` and `ratelimit-policy` (`limit;w=seconds`) — on every
rate-limited response, and `retry-after` on a 429. Through 0.3.0 the SDK typed only the
`Retry-After` hint (ADR-025); the window headers reached a consumer solely through the
`Interceptor` SPI, raw. ADR-006 listed "rate-limit window" metadata from the start and never
implemented it; PROJECT_STATE carried it as deferred "until a consumer needs more than the
`Interceptor` SPI".

Two things made the deferral expensive. The first live 429 (2026-08-28) showed that a caller
holding a `FanarRateLimitException` has no way to tell "one slot frees in 28 606 s" from "the
window is empty" without re-parsing headers it cannot see. And the full live run of 2026-08-29
([WIRE_OBSERVATIONS](../WIRE_OBSERVATIONS.md), "Transport level") established what the headers
actually mean: the windows are **sliding**, per requested model id — `remaining` is the limit minus
the requests made in the trailing `w` seconds, and `reset` is the wait until the *oldest* of them
ages out. It jumps back up when a request leaves the window (`Fanar-Sadiq`: 2 → 8 with `remaining`
unchanged) and is never a boundary. Headers are absent on non-model calls (`/v1/models`,
`/v1/tokens`, voices listing), on rejections before admission (401, 403, the model gate's 422) and
— per the spec — for keys with unlimited quota; a post-admission 4xx (Diwan's verse-miss 422)
carries them and counts.

Where to surface this is an API-shape decision (ADR-019 window): a typed record on the exceptions,
observation attributes, response metadata on every DTO, or a proactive throttle.

## Decision

1. **Observation attributes at the retry boundary.** `RetryInterceptor` — the SDK's error boundary,
   the one place every attempt's response passes through — parses the headers of every response
   the chain returns and records `fanar.ratelimit.limit`, `fanar.ratelimit.remaining`,
   `fanar.ratelimit.reset` (seconds, as on the wire) and `fanar.ratelimit.policy` (the raw value)
   on the call's observation, successes and 429s alike; a later attempt overwrites an earlier one
   ("last attempt wins", like `http.status_code`). Responses without the headers record nothing.
   The four names join `FanarObservationAttributes` — a minor-version addition under ADR-013.
2. **`RateLimitInfo` on both 429 subtypes.** A public record in `qa.fanar.core` —
   `RateLimitInfo(long limit, long remaining, Duration reset, String policy)` with a derived
   `window()` parsed from the policy — is carried by `FanarRateLimitException` and
   `FanarQuotaExceededException` and exposed as `rateLimit()`, `null` when the server sent no
   headers (the same nullable convention as `retryAfter()`, ADR-015: no `Optional` fields). Both
   exceptions gain constructor overloads taking it; the existing ones delegate with `null`
   (additive under ADR-019). `reset` and `retryAfter()` are the same countdown on Fanar; both are
   kept because the spec defines them separately and `retry-after` can also carry an upstream
   service's hint.
3. **Nothing on response DTOs.** `ChatResponse` and friends stay pure wire records (ADR-015).
4. **Cardinality rule for metric-tag backends.** `limit` and `policy` take one value per model —
   bounded, low-cardinality. `remaining` (0..limit) and `reset` (0..86400) are unbounded for tag
   purposes: the Micrometer adapter records them as `highCardinalityKeyValue`s by default and
   exposes `MicrometerObservabilityPlugin.builder(...).highCardinalityKeys(Predicate<String>)` to
   replace the rule. The OpenTelemetry adapter's typed dispatch already records them as `long` /
   `String` span attributes; the SLF4J adapter prints them with the other attributes.
5. **No proactive throttling.** A throttle that models the sliding window is a user-supplied
   interceptor (ADR-012); the attributes and `RateLimitInfo` give it the data. The retry loop's
   only use of the headers stays `Retry-After` (ADR-025).

Parsing is lenient and internal (`internal.transport.RateLimitHeaders`, ADR-018): an instance needs
`limit` and `remaining` as non-negative integers; `reset` and `policy` are optional; garbage in any
header degrades to `null` for that component rather than an exception, and the same parser feeds
the attributes and the exceptions so the two surfaces can never disagree.

## Alternatives considered

- **Attributes only.** *Rejected*: the caller that catches the 429 is the one that has to decide
  what to do next, and it cannot read a dashboard. Half the value at almost the same cost.
- **Exception field only.** *Rejected*: quota headroom on *successful* calls is where a dashboard
  earns its keep — a team wants to see `remaining` trend to zero before the first 429, not after.
- **Response metadata on every DTO** (`ChatResponse.rateLimit()` etc.). *Rejected*: touches every
  record, both codecs and the GraalVM reachability metadata (the ADR-015 grid) to carry information
  that the observation already has; it also drags transport concerns into wire DTOs.
- **A `ThreadLocal` / scoped "last response" side channel.** *Rejected*: invisible coupling, hostile
  to the virtual-thread async path (ADR-004), and exactly the context-smuggling ADR-012 refused.
- **A built-in proactive throttle interceptor.** *Rejected*: ADR-012 keeps built-ins to two; the
  sliding-window semantics mean any correct throttle must track per-model request timestamps —
  policy the SDK should not choose for its users.
- **Record the attributes in the facades instead of the retry boundary.** *Rejected*: the boundary
  sees every attempt including the ones that were retried away; a facade sees only the last
  response and would miss a 429's window entirely.

## Consequences

### Positive
- Dashboards see quota headroom per model on every call (`fanar.ratelimit.remaining`), and a 429
  carries the window it hit — `remaining`, the wait for one slot, and the policy that explains
  why "20" means twenty per trailing 24 hours.
- One parser, two surfaces, one vocabulary; the adapters needed no new SPI.
- Nothing changes for callers that ignore it: new constants, a new record, additive constructors.

### Negative / Trade-offs
- Micrometer users who tag metrics with every attribute now get two high-cardinality key-values;
  the classification is documented and overridable, and the previous behaviour (everything
  low-cardinality) would have been the actual hazard.
- Two ways to read the same countdown on a 429 (`retryAfter()` and `rateLimit().reset()`).
  Accepted: they are defined by different headers with different scopes.

### Neutral
- Attribute values are `long` (counts, seconds) and `String` (policy); `http.status_code` and
  `fanar.retry_count` stay `int`. Adapters that stringify (SLF4J, Micrometer) see no difference.
- `RateLimitInfo` is not decoded from JSON, so it needs no codec support or reachability metadata.

## Proved by

- `FanarClientRetryIntegrationTest.rateLimitHeadersOnA429SurfaceOnTheExceptionAndAsAttributes`,
  `.rateLimitHeadersOnSuccessAreRecordedAndTheLastAttemptWins`,
  `.responsesWithoutRateLimitHeadersRecordNoRateLimitAttributes` — public builder → chain → scripted
  server, the 2026-08-28 exhausted-window shape included.
- `Slf4jObservabilityPluginIntegrationTest`, `OpenTelemetryObservabilityPluginIntegrationTest`,
  `MicrometerObservabilityPluginIntegrationTest` — each adapter receives the window of the last
  attempt through a retried public call; Micrometer on the high-cardinality side.
- `LiveRateLimitHeadersTest.success_carriesRateLimitHeaders` — against Fanar, the attributes mirror
  the headers on the same counted request.
- Units: `RateLimitInfoTest`, `RateLimitHeadersTest`, `ExceptionMapperTest` (`*RateLimitWindow*`),
  `RetryInterceptorTest` (`rateLimit*`), `FanarExceptionTest` (`*CarriesTheWindow`),
  `MicrometerObservabilityPluginTest` (cardinality cases).

## References

- ADR-006 Unchecked exception hierarchy (amended 2026-08-29: `rateLimit()` on both 429 subtypes)
- ADR-012 Interceptor SPI (built-ins stay two; throttling is user-supplied)
- ADR-013 Observability SPI (amended 2026-08-29: the `fanar.ratelimit.*` vocabulary)
- ADR-014 Retry policy defaults / ADR-025 Retry-After handling (the retry loop's use of the headers)
- ADR-015 DTO conventions (no `Optional` fields; DTOs stay pure)
- ADR-019 Pre-1.0 stability policy (additive change)
- [WIRE_OBSERVATIONS](../WIRE_OBSERVATIONS.md) — the header shapes per response class and the
  sliding-window semantics (2026-08-29 run)
- Fanar OpenAPI `info.description`, 2026-08-27 refresh — rate-limit header contract
