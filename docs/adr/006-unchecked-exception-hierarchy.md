# ADR-006 — Unchecked exception hierarchy

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

Fanar reports failures as a typed `ErrorCode` inside an error envelope — `qa.fanar.core.ErrorCode`
mirrors the spec's enumeration, which is the list, not this document. Transport failures
(`IOException`, `InterruptedException`) from JDK `HttpClient` are a separate category. We must
decide how both surface to Java callers.

Two things about the wire shape the design has to survive, both observed rather than assumed
([WIRE_OBSERVATIONS](../WIRE_OBSERVATIONS.md)). Not every failure uses the envelope:
request-validation errors bypass it and return FastAPI's `{"detail":[…]}`, which no envelope parser
will recognise. And not every refusal is an error at all — Fanar's moderation declines inside an
HTTP 200, in ordinary assistant text. The hierarchy therefore has to degrade sensibly when the
envelope is missing, and callers cannot treat it as the only place refusals appear.

The choice interacts with our async (`CompletableFuture<T>`) and streaming (`Flow.Publisher<T>`) surfaces — both of
which have well-defined error channels that compose cleanly with `RuntimeException` subtypes but fight checked
exceptions.

## Decision

All errors surface as **unchecked exceptions** under a single root:

```java
public abstract sealed class FanarException extends RuntimeException
        permits FanarClientException, FanarServerException, FanarTransportException, FanarContentFilterException { … }

// 4xx — the request as sent was rejected.
public abstract sealed class FanarClientException extends FanarException { … }

// 5xx — the server failed. Retried by default.
public abstract sealed class FanarServerException extends FanarException { … }

public final class FanarTransportException extends FanarException { … }        // IOException / InterruptedException
public final class FanarContentFilterException extends FanarException { … }    // content_filter
```

Users catch the subtype they care about:

```java
try {
    var response = client.chat().send(request);
} catch (FanarRateLimitException e) {
    backoff(e.retryAfter());
} catch (FanarContentFilterException e) {
    showRefusalUi(e.filterType());   // nullable — the server does not always send one
} catch (FanarException e) {
    log.error("Fanar call failed", e);
}
```

Transport-layer `IOException` and `InterruptedException` from JDK `HttpClient` are wrapped into `FanarTransportException`
at the transport boundary; callers never see JDK checked exceptions on the public API.

### The leaves, and the rule that produces them

**One leaf per Fanar `ErrorCode`**, filed under the branch its HTTP class implies. Each leaf's own
javadoc names its code and status; the permits clauses in the source are the authoritative list.
Two leaves exist that no `ErrorCode` names:

- `FanarTransportException` — a JDK transport failure, where no response was observed at all.
- `FanarUnexpectedClientException` / `FanarUnexpectedServerException` — a status the wire contract
  does not declare. A proxy answering 407, a gateway answering 405, a future Fanar status this
  build predates. They carry the status **as received** and a `null` `code()`, because inventing
  either would be a lie a caller cannot detect.

**The branch invariant is load-bearing, not cosmetic.** `RetryPolicy.isDefaultRetryable` switches on
the branch, so which side of the 4xx/5xx line a status lands on is what the retry policy reads
(ADR-014 owns that policy, including the two statuses it exempts). An
unmodelled 4xx filed under `FanarServerException` would be retried three times with backoff and
reported to the caller as a server fault — which is what the SDK did before the `Unexpected*` leaves
existed, for every status outside the declared set.

### Routing

The typed `code` in the envelope decides the subtype; HTTP status is the fallback when the body is
not a well-formed envelope or carries a code this build does not know. Envelope-first matters
because two distinct codes share HTTP 429 — throttling and quota exhaustion — and only the code
tells them apart.

Mapping happens **inside the interceptor chain**, at the retry boundary, not in each domain facade
after the chain returns. That is the only arrangement in which the retry policy can act on the typed
hierarchy this ADR defines: a facade that maps after `chain.proceed` leaves the retry loop matching
on raw responses, and the typed path never executes. User interceptors still see raw 4xx/5xx; only
facades are guaranteed typed exceptions (ADR-012).

Decoding of envelope members is permissive per ADR-015: an unrecognised `type` becomes a
`ContentFilterType` carrying the new wire string rather than failing, and a member whose value is
not a string reads as absent. Only `code` is load-bearing — losing a whole envelope, and with it the
typed routing, because an auxiliary member arrived with an unexpected JSON type is a poor trade.
Strictness about JSON *syntax* is unchanged.

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
- The sealed hierarchy means pattern matching on exception types becomes possible in Java 21+, and
  `isDefaultRetryable` is an exhaustive switch over four branches rather than a list of statuses to
  keep in sync.

### Negative / Trade-offs
- Callers unfamiliar with Fanar's error model must consult documentation to know which subtypes exist. Mitigated by
  clear Javadoc and the sealed hierarchy making them discoverable via "show subclasses" in any IDE.
- Forgetting to handle an exception is a compile-success / runtime-surprise. Acceptable — same as every other
  `RuntimeException` in Java.
- Adding a leaf is a breaking change post-1.0 (JLBP-10), so the set has to be right before 1.0.0
  ships. Pre-1.0 it is a minor-version change with a changelog callout (ADR-019).
- A caller cannot distinguish "the server sent no `Retry-After`" from "the SDK could not parse the
  one it sent" — both are `null`. Deliberate: a malformed hint is not better than none.

### Neutral
- Fanar-specific metadata lives as fields on the subtype it belongs to, reachable through typed
  accessors: the `Retry-After` hint and the `RateLimitInfo` window on both HTTP 429 subtypes
  (ADR-025, ADR-026), the content-filter type on `FanarContentFilterException`. `null` where the
  server sent nothing.
- The envelope's `param` is parsed but deliberately **not** surfaced. It belongs on `Error` — that
  is, on every exception — so an accessor would mean new constructor overloads down every leaf, a
  large public-API grid spent on a field observed only ever as `null`. Parsing it now makes the
  later decision cheap. Tracked in [PROJECT_STATE](../PROJECT_STATE.md).
- `type` is carried on the status-fallback route and dropped on the code route, on purpose. A
  recognised code is a better signal than the status *and* tells us the error is not a content
  filter, so a stray `type` is noise. The fallback route has no such signal — an unrecognised code
  leaves only HTTP 400, which this ADR maps to content filtering — so the envelope's own word is the
  best information available. The cost is that a future 400-level code this build has not learned,
  arriving with a `type`, surfaces as a content-filter exception; the coarse 400 → content-filter
  mapping is the older half of that problem.

### A lesson this design paid for twice

Two accessors in this hierarchy shipped as **dead public API** — reachable in the type system,
unreachable at runtime — and both survived a 100 % coverage gate.

`FanarContentFilterException.filterType()` could not return a value for any exception the SDK
produced: the envelope parser read only `code` and `message`, so the spec's `type` was discarded
before the exception was built, and both construction sites used the 1-arg constructor. The public
`ContentFilterType` constants had no code path that could produce them — while this ADR's own worked
example above called `showRefusalUi(e.filterType())`. Earlier, the same shape: the typed hierarchy
was mapped *after* the interceptor chain, so no HTTP-status error ever reached the retry loop that
was written to consume it.

Coverage did not catch either, because coverage measures execution, not wiring. The unit tests built
the exception directly with the 2-arg constructor and asserted the exception *class* per envelope
code — never the accessor, never the seam. What catches this class of defect is a test that crosses
the seam through the public API, which is why every behaviour here names one under *Proved by*.

## Proved by

- `FanarClientErrorEnvelopeIntegrationTest` — the envelope reaching `filterType()` through
  `FanarClient.builder()` → chain → transport → `ScriptedHttpServer`, on both construction sites,
  with a JSON-`null` regression guard.
- `FanarClientRetryIntegrationTest.userInterceptorsSeeRawErrorResponses` — the boundary this ADR
  draws: user interceptors observe raw 4xx/5xx while facades see only typed exceptions.
- `ExceptionMapperTest.unknown4xxMapsToUnexpectedClientAndIsNotRetryable` and
  `.unknownNon4xxMapsToUnexpectedServerAndStaysRetryable` — the branch invariant, asserted together
  with the retry decision it governs.
- `ErrorEnvelopeTest` — per-member tolerance, including the live 403 body verbatim.

## References

- ADR-004 Sync-primary API with async sugar
- ADR-005 Streaming via `Flow.Publisher`
- ADR-007 JDK `HttpClient` as the default transport
- ADR-014 Retry policy defaults (consumes the typed hierarchy)
- ADR-019 Pre-1.0 stability policy (permits the 0.2.0 sealed-hierarchy addition)
- OpenAPI spec § `ErrorCode`, `ErrorStatus`
