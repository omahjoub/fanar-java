# ADR-006 — Unchecked exception hierarchy

- **Status**: Accepted (amended 2026-08-05 — see [Amendments](#amendments))
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
    showRefusalUi(e.filterType());
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
- Fanar-specific metadata (retry-after seconds, filter type, rate-limit window) lives as fields on the relevant
  subtype, retrievable via typed accessors.

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

## References

- ADR-004 Sync-primary API with async sugar
- ADR-005 Streaming via `Flow.Publisher`
- ADR-007 JDK `HttpClient` as the default transport
- ADR-014 Retry policy defaults (consumes the typed hierarchy)
- ADR-019 Pre-1.0 stability policy (permits the 0.2.0 sealed-hierarchy addition)
- OpenAPI spec § `ErrorCode`, `ErrorStatus`
