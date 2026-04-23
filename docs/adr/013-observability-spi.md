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
- **`AutoCloseable` lifecycle**. The SDK opens observations with try-with-resources; `close()` is idempotent.
- **Standardized operation names**. The SDK emits one observation per semantic operation: `fanar.chat`,
  `fanar.chat.stream`, `fanar.audio.speech`, `fanar.audio.transcription`, `fanar.images.generation`,
  `fanar.translations`, `fanar.poems.generation`, `fanar.moderation`, `fanar.tokens`, `fanar.models.list`. Documented
  in the SPI Javadoc.
- **Standardized attributes**. A constants class `qa.fanar.core.spi.FanarObservationAttributes` defines the canonical
  attribute vocabulary: `http.method`, `http.url`, `http.status_code`, `fanar.model`, `fanar.retry_count`,
  `fanar.stream.chunks`, `fanar.stream.first_chunk_ms`. Adapter authors map these consistently.
- **Nested observations** via `.child(operationName)` for phase-level breakdowns (e.g., serialization vs network).
  Maps cleanly to OpenTelemetry parent/child spans and Micrometer nested observations.
- **Context propagation** via `propagationHeaders()` — the SDK queries the handle for trace-context headers (e.g.,
  W3C `traceparent`) and merges them into the outbound request before interceptors run.
- **Default is a no-op plugin** — zero work, zero allocation, zero visible effect. Users opt into concrete
  implementations via downstream adapter modules.
- **Exposed from `Chain.observation()`** (ADR-012) so interceptors can attach events without context-passing magic.

### What the SDK does not emit by default

- **No per-stream-event observations**. Streaming emits `first_chunk_ms` and a final `stream.chunks` count at close;
  per-chunk tracing would be too noisy and is composable at the publisher layer.
- **No built-in logging plugin**. `System.Logger` (JDK-built-in) is always available; structured logging bindings
  are the responsibility of downstream adapter modules.
- **No OpenTelemetry or Micrometer types in core**. Adapter modules (future, not part of v1) provide these.

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
- `AutoCloseable` + try-with-resources is natural Java; idempotent `close()` is safe under all exception paths.
- Nested observations (`child(...)`) give users phase-level insight without forcing callers to orchestrate spans.
- Future adapter modules (Micrometer, OpenTelemetry, SLF4J-logging, in-memory test plugin) are thin — each ~100
  lines wrapping the concrete backend.

### Negative / Trade-offs
- `Chain.observation()` creates a soft coupling between the interceptor SPI (ADR-012) and this SPI. The alternative
  context-propagation mechanisms (ThreadLocal, `ScopedValue` preview) are worse.
- The attribute vocabulary in `FanarObservationAttributes` is a contract — adding new canonical names is a minor
  version bump; renaming is a major.

### Neutral
- Adapter implementations are out-of-scope for `fanar-core`'s initial release; they arrive as separate modules.

## References

- ADR-002 Narrow core SDK scope
- ADR-003 Framework-agnostic public API
- ADR-004 Sync-primary API with async sugar
- ADR-012 Interceptor SPI
- ADR-018 Internals are not a contract
- OpenTelemetry Tracing specification
- Micrometer `Observation` API documentation
