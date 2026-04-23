# ADR-007 — JDK `HttpClient` as the default transport

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

The SDK must make HTTP calls to `https://api.fanar.qa`. The Java ecosystem offers multiple HTTP-client libraries,
each with a different maturity, footprint, and ergonomic profile: OkHttp, Apache HttpClient 5, the JDK's
`java.net.http.HttpClient`, and several niche options. We must pick a default and decide whether (and how) to allow
replacement.

Our constraints:
- Zero runtime dependencies in core (ADR-002, JLBP-1).
- First-class support for virtual threads (ADR-004) and `Flow.Publisher<T>` streaming (ADR-005).
- GraalVM native-image compatibility (ADR-009).
- `AutoCloseable` for clean lifecycle management in try-with-resources.

## Decision

**JDK `java.net.http.HttpClient` is the default transport**. Each `FanarClient` owns one shared `HttpClient`
instance, long-lived and configurable via the builder. Users may pass their own pre-configured `HttpClient` via
`.httpClient(custom)`, in which case they retain lifecycle responsibility (ADR-016).

If a future need for a non-JDK transport arises, the transport surface can be factored into an internal SPI without
breaking the public API (per ADR-018, `internal.*` is free to refactor). For now, the API surface exposes `HttpClient`
directly as the configuration point.

## Alternatives considered

- **OkHttp**. Mature, ubiquitous in Android and many server-side stacks, excellent SSE support via add-ons.
  *Rejected*: adds OkHttp + Okio as runtime deps (~1 MB), contradicts zero-deps posture, drags its own version-resolution
  politics into our classpath.
- **Apache HttpClient 5**. Battle-tested, rich feature set. *Rejected*: heavier transitive footprint than OkHttp,
  slower async model than JDK's `HttpClient`, no meaningful feature advantage we'd use.
- **Netty directly**. *Rejected*: enormous overkill for a client SDK; event-loop programming model violates our
  sync-primary stance (ADR-004).
- **Pluggable transport SPI from day one** (multiple transports supported at launch). *Rejected*: premature
  complexity; we can introduce an SPI later without breaking callers since the transport is already replaceable at
  the `.httpClient(custom)` level, and internal refactors are safe per ADR-018.

## Consequences

### Positive
- Zero runtime dependencies (JLBP-1).
- Native HTTP/2 support with no extra configuration.
- `Flow.Publisher<T>` body publishers integrate directly with the streaming surface (ADR-005).
- `HttpClient` is `AutoCloseable` from Java 21 (ADR-001), allowing clean `FanarClient.close()` cascade.
- First-class support in GraalVM native-image (ADR-009); no reachability tricks needed.
- Users who need specific behavior (custom `SSLContext`, corporate proxies, custom thread pools, authentication
  through a `java.net.Authenticator`) pass a configured `HttpClient` through the builder without fighting the SDK.

### Negative / Trade-offs
- JDK `HttpClient` lacks some conveniences present in OkHttp (connection-pool introspection, interceptor-style logging
  without filters). We fill the interceptor gap via our own SPI (ADR-012); logging/metrics belong to the observability
  SPI (ADR-013).
- Users who *must* use a non-JDK HTTP client (for instance, a project standardized on OkHttp with custom connection
  management) need to wait for a transport SPI. Acceptable — they can also wrap their own HTTP client around a
  user-supplied `FanarClient` interceptor if truly blocked.

### Neutral
- Per-request timeouts are a per-client setting for v1 (ADR-016). Per-request overrides may be added later as a
  non-breaking addition.

## References

- ADR-002 Narrow core SDK scope
- ADR-004 Sync-primary API with async sugar
- ADR-005 Streaming via `Flow.Publisher`
- ADR-009 GraalVM native-image as a day-one CI target
- ADR-016 `FanarClient` builder and domain facades
- ADR-018 Internals are not a contract
