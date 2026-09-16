# ADR-022 — Observability composition via `compose(...)` factory

- **Status**: Accepted
- **Date**: 2026-04-28
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
- **Rejects a `null` child at construction, and contains a throwing one at call time.** The two are
  opposite failures and get opposite treatment. A `null` in the list is a wiring mistake: caught
  immediately, with a message naming the problem, because it will never become correct. An exception
  from a live plugin is a runtime condition — a metrics backend down, a tracer misconfigured — and
  telemetry is not worth a failed request. It is absorbed so the caller's call succeeds and the
  sibling plugins still observe the rest of the lifecycle. A plugin that fails to `start`, or returns
  `null`, keeps a silent slot so the fan-out stays aligned for every later call.
- `Error` is **not** contained. An `OutOfMemoryError` from a plugin is not the plugin's to recover
  from, and swallowing it would hide a failure the application needs to see.
- The containment here is **per-child**: one broken backend does not blind its siblings, which is the
  failure mode that makes composition worth having at all. It is distinct from the client's
  per-call guard (ADR-013), which stops any plugin's failure reaching the request. Neither implies
  the other, and both are needed: the per-call guard would still let one broken child abort the
  fan-out loop before its siblings were called.

- Returns `noop()` for an empty list and the plugin itself, unwrapped, for a list of one — so
  composing costs nothing when there is nothing to compose. The unwrap is safe because it is no
  longer load-bearing for failure containment: the client guards whatever plugin it is given
  (ADR-013), so a lone plugin is protected whether it went through this factory or not.

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

## Proved by

- `CompositeObservabilityPluginTest` — fan-out across the whole handle lifecycle, and the
  containment rules: a throwing child neither fails the caller nor silences its siblings, a child
  that throws on `start` or returns `null` gets a silent slot.

## References

- [`ObservabilityPlugin.compose`](../../core/src/main/java/qa/fanar/core/spi/ObservabilityPlugin.java)
- [`CompositeObservabilityPlugin`](../../core/src/main/java/qa/fanar/core/internal/observability/CompositeObservabilityPlugin.java)
- ADR-013 — observability SPI (single-slot model).
- ADR-012 — interceptor SPI (chain model — contrast).
