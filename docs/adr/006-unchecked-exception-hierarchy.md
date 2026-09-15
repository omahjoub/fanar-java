# ADR-006 — Unchecked exception hierarchy

- **Status**: Accepted (amended 2026-08-05, 2026-08-28, 2026-08-29 and 2026-09-15 — see [Amendments](#amendments))
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

The Fanar API returns a typed `ErrorCode` enumeration (`content_filter`, `invalid_authentication`, `rate_limit_reached`,
`exceeded_quota`, `internal_server_error`, `overloaded`, `timeout`, `too_large`, `unprocessable`, `conflict`,
`Not found`, `no_longer_supported`, `client_closed_request`). Transport failures (`IOException`,
`InterruptedException`) from JDK `HttpClient` are a separate category. We must decide how these errors surface to
Java callers.

The choice interacts with our async (`CompletableFuture<T>`) and streaming (`Flow.Publisher<T>`) surfaces — both of
which have well-defined error channels that compose cleanly with `RuntimeException` subtypes but fight checked
exceptions.

## Decision

All errors surface as **unchecked exceptions** under a single root:

```java
public abstract sealed class FanarException extends RuntimeException
        permits FanarClientException, FanarServerException, FanarTransportException, FanarContentFilterException { … }

public sealed class FanarClientException extends FanarException
        permits FanarAuthenticationException, FanarAuthorizationException, FanarQuotaExceededException,
                FanarNotFoundException, FanarConflictException, FanarTooLargeException,
                FanarUnprocessableException, FanarGoneException, FanarClientClosedRequestException { … }

public sealed class FanarServerException extends FanarException
        permits FanarRateLimitException, FanarOverloadedException, FanarTimeoutException, FanarInternalServerException { … }

public final class FanarTransportException extends FanarException { … }        // wraps IOException / InterruptedException
public final class FanarContentFilterException extends FanarException { … }    // content_filter
```

Users catch the subtype they care about:

```java
try {
    var response = client.chat().send(request);
} catch (FanarRateLimitException e) {
    backoff(e.retryAfter());
} catch (FanarContentFilterException e) {
    showRefusalUi(e.filterType());   // nullable — see the 2026-09-15 amendment
} catch (FanarException e) {
    log.error("Fanar call failed", e);
}
```

Transport-layer `IOException` and `InterruptedException` from JDK `HttpClient` are wrapped into `FanarTransportException`
at the transport boundary; callers never see JDK checked exceptions on the public API.

## Alternatives considered

- **Checked exceptions** (`throws FanarException` on every method). *Rejected*: hostile to `CompletableFuture.thenApply`
  and `Flow.Publisher.onError`, forces `try/catch` noise throughout functional pipelines, and breaks lambda-based
  callers.
- **`Result<T, FanarError>` sealed type** (explicit success/failure). *Rejected*: alien to mainstream Java; forces
  callers into a style the JDK itself doesn't use. Re-evaluable later as an optional wrapper if requested.
- **Single `FanarException` with an `errorCode` field** (no subtypes). *Rejected*: loses the ability to `catch` by
  semantic category, requires string or enum comparison at every call site, and feels like a step backwards from
  Java idioms.

## Consequences

### Positive
- Clean composition with async (`CompletableFuture.exceptionally`, `exceptionallyCompose`) and streaming
  (`Flow.Subscriber.onError`) — unchecked exceptions flow through these channels without wrapping.
- Users `catch` precisely the subtype they care about; others propagate naturally.
- Predictable mapping: each `ErrorCode` has exactly one exception subtype, documented in Javadoc.
- The sealed hierarchy means pattern matching on exception types becomes possible in Java 21+.

### Negative / Trade-offs
- Callers unfamiliar with Fanar's error model must consult documentation to know which subtypes exist. Mitigated by
  clear Javadoc and the sealed hierarchy making them discoverable via "show subclasses" in any IDE.
- Forgetting to handle an exception is a compile-success / runtime-surprise. Acceptable — same as every other
  `RuntimeException` in Java.

### Neutral
- Fanar-specific metadata (the `Retry-After` hint and the rate-limit window on both HTTP 429 subtypes, the
  content-filter type) lives as fields on the relevant subtype, retrievable via typed accessors — the window as
  `rateLimit()` since ADR-026 (2026-08-29), `null` when the server sent no headers.

## Amendments

### 2026-08-05 — `client_closed_request` and envelope-code routing (0.2.0)

The Fanar spec added a fourteenth error code, `client_closed_request` (HTTP 499, declared on every
endpoint). Following this ADR's one-subtype-per-`ErrorCode` rule, 0.2.0 adds
`FanarClientClosedRequestException` as a leaf under `FanarClientException` — deliberately *not* a
new top-level branch, so retry classification (`RetryPolicy.isDefaultRetryable`) and consumer
switches over the four top-level categories keep compiling, and the new code is correctly
non-retryable. Adding a permit to a sealed class is a breaking change under JLBP-10; ADR-019's
pre-1.0 policy allows it in a minor release with a changelog callout, which 0.2.0 carries.

The same release implements the routing this ADR always implied: `ExceptionMapper` now parses the
Fanar error envelope (`{"error":{"code":…,"message":…,"status":…}}`) and routes by the typed
`ErrorCode` first, falling back to HTTP status when the body is not a well-formed envelope or
carries an unknown code. This makes `FanarQuotaExceededException` reachable (both quota exhaustion
and throttling wire as HTTP 429) and stops non-filter 400s from surfacing as
`FanarContentFilterException`.

### 2026-08-28 — Mapping at the retry boundary; `Retry-After` on both 429 subtypes (0.3.0)

The mapper now runs inside the interceptor chain, in `RetryInterceptor`, rather than in each
domain facade after the chain returns — the only way the retry policy can act on the typed
hierarchy this ADR defines (ADR-012 amendment). Routing is unchanged: envelope `code` first,
HTTP status as fallback.

`FanarQuotaExceededException` gains `retryAfter()` alongside `FanarRateLimitException`: the spec's
`retry-after` "counts down to a free slot" on either 429 code, and the exhausted-daily-window case
is exactly where a caller needs it (ADR-025). The mapper normalises the header — non-positive
seconds, past HTTP-dates and unparseable values become `null`; a future HTTP-date becomes the
remaining wait. Additive under ADR-019. The "rate-limit window" metadata this ADR originally
listed was never implemented and is now explicitly deferred (PROJECT_STATE).

### 2026-08-29 — `RateLimitInfo` on both 429 subtypes (0.4.0)

The deferral above ends with ADR-026: both 429 subtypes gain `rateLimit()`, a nullable
`RateLimitInfo` (`limit`, `remaining`, `reset`, raw `policy`, derived `window()`) parsed from the
`x-ratelimit-*` / `ratelimit-policy` headers by the same internal parser that feeds the
`fanar.ratelimit.*` observation attributes. New constructor overloads carry it; the existing ones
delegate with `null` — additive under ADR-019. `reset` is the wait until one slot frees in a
sliding window, never a boundary ([WIRE_OBSERVATIONS](../WIRE_OBSERVATIONS.md)).

### 2026-09-15 — the envelope's `type` reaches `filterType()` (0.5.0)

`FanarContentFilterException.filterType()` had been **dead public API since this ADR shipped**: it
could not be non-`null` for any exception the SDK produced. `ErrorEnvelope` was a two-member record
(`code`, `message`), so the spec's `status`, `param` and `type` all fell through to `skipValue()`,
and both sites that build the exception — the typed `content_filter` route and the HTTP-400 fallback
— called the 1-arg constructor. The public three-constant `ContentFilterType` therefore had no code
path that could produce it, while this ADR's own worked example above read
`showRefusalUi(e.filterType())`.

It survived the 100 % coverage gate exactly as the dead retry path did in 0.2.0: `FanarExceptionTest`
built the exception directly with the 2-arg constructor, and `ExceptionMapperTest` asserted only the
exception *class* per envelope code, never the accessor. Neither test crossed the seam where the
wiring lives.

The mapper now reads `type` off the envelope and passes it to the 2-arg constructor at both sites.
Mapping is permissive per ADR-015 — a value the SDK ships no constant for decodes into a
`ContentFilterType` carrying the new wire string rather than failing; absent, JSON-`null` and blank
all mean "the server provided none" and yield `null`. A `type` on a non-filter code is dropped: this
ADR keeps metadata on the subtype it belongs to, and `ErrorContentFilterType` is a content-filter
discriminator by name and by enum.

The envelope parser also became tolerant **per member**, which is load-bearing rather than
defensive. `param` and `type` are declared nullable by the spec and were both observed `null` on the
wire (2026-09-15 403, [WIRE_OBSERVATIONS](../WIRE_OBSERVATIONS.md)); reading them with the parser's
plain string reader throws, which discards the *whole* envelope and silently drops the response to
HTTP-status routing. Adding the naive `case "type" -> type = string()` would have regressed a path
that worked.

The tolerance is deliberately wider than `null`: a member whose value is any non-string reads as
absent, by delegating to the scanner's existing `skipValue()`. Only `code` is load-bearing — it
picks the subtype — and losing that routing because an auxiliary member arrived with an unexpected
JSON type is a poor trade. Strictness about JSON *syntax* is unchanged: an unbalanced container or a
bad escape still fails the parse, and a non-string `code` still yields no envelope, so status
routing takes over exactly as before.

`param` is now parsed but deliberately **not surfaced**. It belongs on `Error`, i.e. on every
exception, so a `FanarException.param()` accessor would mean new constructor overloads down all
fifteen leaf subtypes — a large public-API grid spent on a field observed once, as `null`. Parsing
it now makes the later surface decision cheap; it is tracked in
[PROJECT_STATE](../PROJECT_STATE.md) as Planned.

Additive under ADR-019: no signature changed, and an accessor that could only return `null` can now
also return a value.

The two routes treat `type` differently on purpose. `byCode` drops it for every non-filter code: a
recognised code is a better signal than the status, and it says the error is not a content filter.
`byStatus` has no such signal — an unrecognised code leaves only HTTP 400, which this ADR maps to
content filtering — so the envelope's `type` is the best information available and is carried, at
the trust level that route already extends to the envelope's `message`. The cost: a future
400-level code the SDK has not learned yet, arriving with a `type`, surfaces as a
`FanarContentFilterException` reporting that subtype. The coarse 400 → content-filter mapping is the
older half of that and is this ADR's to revisit, not this amendment's.

**Proved by** `FanarClientErrorEnvelopeIntegrationTest` (core, `@Tag("integration")`) — the envelope
reaching `filterType()` through `FanarClient.builder()` → chain → transport → `ScriptedHttpServer`,
on both construction sites, plus the JSON-`null` regression guard. Units: `ErrorEnvelopeTest`
(`parsesParamAndType`, `parsesTheLive403ErrorObject`, `jsonNullReadsAsAbsentInEveryMember`),
`ExceptionMapperTest` (the content-filter-type block).

**Confirmed live, same day.** A targeted probe (four chat calls) settled three things this amendment
had to assume. The envelope *is* wrapped in `{"error":{…}}` and typed-code routing demonstrably fires
against real Fanar — a gated-model 422 came back as `{"error":{"code":"unprocessable","message":"Model
not authorized","status":422,"param":null,"type":null}}` and was routed through `byCode`. That had
never been proved: every error the live suite provokes (401, the Diwan 422, the Sadiq-2 422) maps to
the same exception under envelope *and* status routing, so a total parse failure would have been
invisible.

Two findings cut against the feature this amendment fixes, and both belong here rather than in a
changelog note:

1. **Fanar's moderation does not use this error path at all.** Two prompts written to trip a safety
   layer both returned **HTTP 200** with the model declining in ordinary `TextContent` — no
   `content_filter` 400, no `FinishReason.CONTENT_FILTER`, no `RefusalPart`. So
   `FanarContentFilterException` may never be constructed in practice, and `filterType()` with it.
   The accessor is now correct rather than dead, which is worth having; it is not, on current
   evidence, worth building refusal handling on.
2. **A second error shape exists that this ADR does not model.** Request-validation failures bypass
   the envelope entirely and return FastAPI's `{"detail":[{"loc":["body","model"],"msg":…}]}`, which
   `tryParse` rejects, so routing falls back to HTTP status and the exception message becomes the raw
   JSON blob. Correct subtype, unreadable message — a candidate for its own cycle.

Both are dated rows in [WIRE_OBSERVATIONS](../WIRE_OBSERVATIONS.md). Together they also settle the
`param` question above: it is `null` on every envelope captured, and the errors that *would* name an
offending field don't use the envelope — they name it in FastAPI's `loc`.

## References

- ADR-004 Sync-primary API with async sugar
- ADR-005 Streaming via `Flow.Publisher`
- ADR-007 JDK `HttpClient` as the default transport
- ADR-014 Retry policy defaults (consumes the typed hierarchy)
- ADR-019 Pre-1.0 stability policy (permits the 0.2.0 sealed-hierarchy addition)
- OpenAPI spec § `ErrorCode`, `ErrorStatus`
