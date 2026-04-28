# ADR-022 — Observability composition via `compose(...)` factory

- **Status**: Accepted
- **Date**: 2026-04-25
- **Deciders**: @omahjoub

## Context

ADR-013 chose a **single observability slot** on `FanarClient` (one `ObservabilityPlugin`,
constructor-injected). That keeps the SPI surface narrow and removes ambiguity about ordering.
Once three observability adapters shipped (`obs-slf4j`, `obs-otel`, `obs-micrometer`), users
want to wire all three at once — structured logs **and** OTel spans **and** Micrometer
observations — without giving up the single-slot model.

Two approaches:

1. **Add a list-style `addObservability(...)` method**, like the interceptor chain. Mirrors
   ADR-012's `Interceptor`. Changes the SPI contract (now stateful slot vs. additive list).
2. **Provide a `compose(...)` factory** that returns one `ObservabilityPlugin` fanning out to N
   children. The slot stays single; users supply the composition.

The semantics are different: interceptors are an **ordered chain** (each wraps the next), while
observability is **fan-out** (every observer sees every event independently, no chaining).
Conflating them in the API would invite bugs around order-of-effects users don't actually want.

## Decision

Ship `ObservabilityPlugin.compose(ObservabilityPlugin... plugins)` as a public static factory.

```java
.observability(ObservabilityPlugin.compose(slf4j, otel, micrometer))
```

The factory returns an internal `CompositeObservabilityPlugin` that:

- Fans out `start(opName)` to each child, returning a composite `ObservationHandle` that fans
  out `attribute` / `event` / `error` / `child` / `close`.
- Merges `propagationHeaders()` last-write-wins on key collision (this rarely matters — different
  observability backends own different header namespaces).
- Tolerates child `null` / failures defensively: a thrown exception from one child does not
  prevent the others from observing the rest of the lifecycle.

The slot remains single; the SPI shape unchanged.

## Alternatives considered

- **`List<ObservabilityPlugin>` slot.** Forces every consumer with a single plugin to write
  `List.of(plugin)`. Loses the "one plugin = one observation backend" mental model. Most users
  wire zero or one plugin; the factory keeps that path simple.
- **`addObservability(plugin)` builder method.** Same problem dressed differently. Plus, the
  builder method implies ordering matters (which it doesn't for fan-out).
- **Tell users to write their own composer.** Three lines per project they shouldn't have to
  write. We ship the obvious thing.

## Consequences

- ✅ Same SPI shape as ADR-013; no breaking change.
- ✅ Single line wires N adapters; explicit user intent at the call site.
- ✅ Children can come and go freely — `compose(slf4j)` and `compose(slf4j, otel)` are both
  valid; users add OTel later by changing one argument.
- ⚠ A buggy or slow child slows every observation. No backpressure / async fan-out. Acceptable
  because the existing adapters are all in-process and cheap; if a user wires a heavyweight
  backend, they own the cost.
- ⚠ `propagationHeaders()` last-write-wins is a small footgun. Mitigated by adapters owning
  disjoint header namespaces (`traceparent` for OTel, etc.) — flagged in adapter Javadoc rather
  than in the composite logic.

## References

- [`ObservabilityPlugin.compose`](../../core/src/main/java/qa/fanar/core/spi/ObservabilityPlugin.java)
- [`CompositeObservabilityPlugin`](../../core/src/main/java/qa/fanar/core/internal/observability/CompositeObservabilityPlugin.java)
- ADR-013 — observability SPI (single-slot model).
- ADR-012 — interceptor SPI (chain model — contrast).
