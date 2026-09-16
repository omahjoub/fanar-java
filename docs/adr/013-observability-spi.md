# ADR-013 — Observability SPI

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

Users need metrics and tracing across the SDK's operations. The landscape is fragmented: AWS SDK splits metrics
(`MetricPublisher`) from tracing (`ExecutionInterceptor`); Azure SDK has `Tracer` + `MeterProvider`; Spring AI
directly uses Micrometer's `Observation`; Google Cloud Java couples to OpenTelemetry; LangChain4j uses a
semantic-event listener. Modern observability is converging on a unified model (OpenTelemetry spans, Micrometer
`Observation`) where metrics and traces share a lifecycle.

Our constraints:
- Zero runtime dependencies in core (ADR-002, JLBP-1).
- Framework-agnostic — no Micrometer, OpenTelemetry, or logging-framework types on the public API (ADR-003).
- Virtual-thread-friendly (ADR-004).
- Usable from interceptors (ADR-012) for concerns like retry counting.

## Decision

A single unified SPI expressing the convergent metrics+tracing model:

```java
package qa.fanar.core.spi;

import java.util.Map;

public interface ObservabilityPlugin {
    ObservationHandle start(String operationName);
    static ObservabilityPlugin noop() { return NoopObservabilityPlugin.INSTANCE; }
}

public interface ObservationHandle extends AutoCloseable {
    ObservationHandle attribute(String key, Object value);
    ObservationHandle event(String name);
    ObservationHandle error(Throwable error);
    ObservationHandle child(String operationName);
    Map<String, String> propagationHeaders();
    @Override void close();
}
```

### Shape rules

- **One plugin per `FanarClient`**. Unlike interceptors (which chain), observability has a single implementation slot.
- **`AutoCloseable` lifecycle**. The SDK closes every handle it opens: a one-shot call with
  try-with-resources, a streaming call when the publisher reaches its terminal signal (see below).
  `close()` is idempotent.
- **Standardized operation names**, shaped `fanar.<domain>.<operation>` — `fanar.chat.send`,
  `fanar.models.list`, `fanar.moderations.score`, and so on for every facade. A streaming variant
  appends `.stream` (`fanar.chat.stream`, `fanar.audio.speech.stream`) so its metrics separate from
  the one-shot call's: the two have different latency profiles and conflating them makes both
  unreadable. Each facade implementation owns its own name as a constant; this ADR fixes the shape,
  not the list, because a list here rots the moment a facade is added.
- **Standardized attributes.** `qa.fanar.core.spi.FanarObservationAttributes` holds the canonical
  vocabulary — HTTP metadata, the model a call addressed, the retry count, the two stream measures
  above, and the rate-limit window the retry boundary reads off the server's headers (ADR-026).
  The constants class is the list; adapter authors map whatever it declares. Each name is
  classified by cardinality when it is added, because some of them must never become metric tags —
  see *Neutral* below.
- **Nested observations** via `.child(operationName)` for phase-level breakdowns (serialization vs
  network, say), mapping to OpenTelemetry parent/child spans and Micrometer nested observations.
  The SDK opens no child observations itself today; the method exists so an interceptor or a future
  phase breakdown can, and adapters must implement it.
- **Context propagation** via `propagationHeaders()` — the SDK queries the handle for trace-context headers (e.g.,
  W3C `traceparent`) and merges them into the outbound request before interceptors run.
- **Default is a no-op plugin** — zero work, zero allocation, zero visible effect. Users opt into concrete
  implementations via downstream adapter modules.
- **A plugin cannot fail a call.** The client guards whatever plugin it is given, so a
  `RuntimeException` from any SPI method is absorbed and the request proceeds; a plugin that throws
  from `start`, or returns `null`, still yields a usable handle. `Error` is not caught — it is not
  the plugin's to recover from. The failure is not silent: the first from a given plugin is reported
  at `WARNING` through `System.Logger`, which is JDK-built-in and so costs core no dependency
  (ADR-002), and later ones drop to `DEBUG` so a plugin that throws on every call cannot bury the
  first report.
  This is unconditional by design. The plugins users install are adapters over networked backends —
  an exporter queue filling, a registry rejecting a duplicate meter name — and "must not throw" is
  not a promise those backends can keep. Trading a paid API call for a metrics hiccup is never the
  right exchange, and a guarantee that held only for *some* ways of installing a plugin would be
  worse than none: it was previously true only of two-or-more composed plugins, so composing more
  made you safer, which is exactly backwards.
- **Exposed from `Chain.observation()`** (ADR-012) so interceptors can attach events without context-passing magic.

### What the SDK does not emit by default

- **No per-stream-event observations.** A streaming observation spans the whole stream rather than
  the handshake that opened it: the publisher takes ownership of the handle, records
  `fanar.stream.first_chunk_ms` when the first item arrives — measured at arrival, so it is the
  server's time-to-first-token and not the subscriber's back-pressure — and `fanar.stream.chunks`
  when the stream ends, then closes the handle on completion, failure and cancellation alike. One
  observation per stream, not per chunk; per-chunk tracing would be noise and composes at the
  publisher layer anyway. The consequence a caller sees is that a stream which dies mid-flight is
  reported as a failure rather than as the clean success its handshake was.
- **No built-in logging plugin**. `System.Logger` (JDK-built-in) is always available; structured logging bindings
  are the responsibility of downstream adapter modules.
- **No OpenTelemetry or Micrometer types in core**. The `fanar-obs-*` adapter modules provide these,
  with their backend dependencies at `provided` scope.

## Alternatives considered

- **Split metrics and tracing SPIs** (AWS-style). *Rejected*: the industry is converging on unified observations;
  two SPIs doubles maintenance without matching the converging mental model.
- **Bake Micrometer into core**. *Rejected*: violates zero-deps (ADR-002) and framework-agnosticism (ADR-003).
- **Bake OpenTelemetry into core**. *Rejected*: same reasons.
- **Event-listener pattern** (`onRequestStart`, `onRequestEnd`, `onRetry`, …) à la OkHttp `EventListener`.
  *Rejected*: grows the interface surface every time we add a new event. Our `attribute`/`event`/`error` triplet is
  an open envelope — adding a new attribute or event name requires no SPI change.
- **ThreadLocal for observation context** (so interceptors can reach it without a parameter). *Rejected*: fragile
  with virtual-thread propagation, and `ScopedValue` (the safe replacement) is preview in Java 21. We extend `Chain`
  instead (ADR-012).

## Consequences

### Positive
- Unified metrics+tracing matches where the industry is converging (OpenTelemetry, Micrometer `Observation`).
- Zero deps in core (JLBP-1).
- `AutoCloseable` is natural Java; idempotent `close()` is safe under all exception paths, which is
  what lets a streaming publisher own the handle without risking a double close.
- Nested observations (`child(...)`) leave room for phase-level insight without forcing callers to
  orchestrate spans themselves.
- The adapter modules (`fanar-obs-slf4j`, `fanar-obs-otel`, `fanar-obs-micrometer`) are thin wrappers
  around their backends — a few hundred lines each, most of it typed attribute dispatch.

### Negative / Trade-offs
- `Chain.observation()` creates a soft coupling between the interceptor SPI (ADR-012) and this SPI. The alternative
  context-propagation mechanisms (ThreadLocal, `ScopedValue` preview) are worse.
- The attribute vocabulary in `FanarObservationAttributes` is a contract — adding new canonical names is a minor
  version bump; renaming is a major.

### Neutral
- Adapter implementations live outside `fanar-core`, one module per backend, so core never sees a
  vendor type.
- The vocabulary distinguishes cardinality, which it did not originally: `fanar.ratelimit.remaining`
  and `.reset` have unbounded value spaces and must never become metric tags. The Micrometer adapter
  records them as high-cardinality by default and exposes `highCardinalityKeys(Predicate)` to change
  the rule; OpenTelemetry's typed dispatch and the SLF4J adapter need nothing. Any attribute added
  from here is classified the same way before it ships.

## Proved by

- `FanarClientObservabilityIsolationIntegrationTest` — through `FanarClient.builder()`: a plugin
  throwing from every SPI method does not fail the call, by either installation route (set directly,
  or via `compose`); a genuine Fanar error still reaches the caller as itself; a healthy plugin still
  observes everything.
- `MicrometerObservabilityPluginIntegrationTest` — a real `FanarClient` over a scripted server
  produces one observation named for the operation, stopped, carrying the retry event and the
  rate-limit attributes.
- `SseStreamPublisherTest` / `AudioStreamPublisherTest` — the streaming observation records both
  stream measures and closes exactly once, on completion, on failure (with the error recorded) and
  on cancellation.
- `FanarClientRetryIntegrationTest.connectionDropMidStreamIsNotRetried` — a stream that dies
  mid-flight reaches the observation as a failure, through the public API.

## References

- ADR-002 Narrow core SDK scope
- ADR-003 Framework-agnostic public API
- ADR-004 Sync-primary API with async sugar
- ADR-012 Interceptor SPI
- ADR-018 Internals are not a contract
- OpenTelemetry Tracing specification
- Micrometer `Observation` API documentation
