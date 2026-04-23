# ADR-003 — Framework-agnostic public API

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

The Java AI-SDK ecosystem is fragmented: Spring users expect Reactor types, Spring AI users expect Micrometer
`Observation`, LangChain4j users expect its `ChatLanguageModel` abstraction, Quarkus users expect CDI beans,
plain-JDK users want nothing beyond what ships with the JVM. If our public API commits to any one of these
third-party type systems, consumers outside that ecosystem pay an adoption tax or are locked out entirely.

A universal SDK must adopt a posture where any framework can wrap it without modification, and no framework is a
first-class citizen over the others.

## Decision

The public API of `fanar-core` uses **only JDK types and our own DTOs**. No third-party types appear on method
signatures, field types, or exception hierarchies.

Permitted on the public surface:
- JDK types: `Flow.Publisher`, `CompletableFuture`, `Stream`, `URI`, `Duration`, `InputStream`, `HttpRequest`,
  `HttpResponse`, `Predicate`, `Supplier`, `AutoCloseable`, primitives.
- Our own types: DTO records, sealed unions, exception subtypes, SPI interfaces, enums.

Explicitly disallowed: any type from Reactor (`Flux`, `Mono`), RxJava (`Flowable`, `Observable`), Spring, Micrometer
(`Observation`, `Meter`), OpenTelemetry, Jackson (`ObjectMapper`, `JsonNode`), or any other third-party library.

Framework-specific affordances are the responsibility of downstream modules, which wrap the core's JDK-typed surface
and expose framework-idiomatic APIs on top (for example, converting `Flow.Publisher<StreamEvent>` to `Flux<StreamEvent>`
in a Reactor-oriented adapter via a one-liner).

## Alternatives considered

- **Expose Reactor types** directly (`Flux`, `Mono`). *Rejected*: drags all consumers into Reactor, contradicts
  zero-runtime-deps (ADR-002), and forecloses a LangChain4j-native or plain-JDK integration.
- **Expose multiple variants** (a Reactor variant, an RxJava variant, a plain variant). *Rejected*: multiplies the
  maintenance surface; each variant needs its own adapters and test matrix.
- **Expose a "neutral" third-party abstraction** such as `reactive-streams.Publisher`. *Rejected*: `Flow.Publisher`
  is JDK-native and API-compatible with `reactive-streams` via `FlowAdapters`, making the third-party dep redundant.

## Consequences

### Positive
- Universal reach: Spring, Spring AI, LangChain4j, Quarkus, plain-JDK, Kotlin, GraalVM native-image consumers all
  work without compromise.
- Zero third-party runtime dependencies in core (JLBP-1).
- Adapters live in downstream modules where they belong, keeping core stable.
- Interop with reactive libraries is a one-liner on the caller's side: `Flux.from(publisher)`.

### Negative / Trade-offs
- Reactor-native users write one extra `Flux.from(...)` call where a direct `Flux` return would skip it. Acceptable
  price for universality.
- Some observability and tracing integrations need a thin adapter module (ADR-013) rather than a direct dependency.

### Neutral
- The rule is enforced by the module boundary (ADR-018) and JLBP-2 checklist items.

## References

- [`docs/JAVA_LIBRARY_BEST_PRACTICES.md`](../JAVA_LIBRARY_BEST_PRACTICES.md) § minimize API surface
- [`docs/COMPATIBILITY.md`](../COMPATIBILITY.md) — "universal" posture in §2
- ADR-002 Narrow core SDK scope
- ADR-018 Internals are not a contract
