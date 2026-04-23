# ADR-005 — Streaming via `Flow.Publisher<StreamEvent>`

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

The Fanar chat-completions endpoint streams Server-Sent Events with a typed discriminated union of chunk shapes:
`TokenChunk`, `ToolCallChunk`, `ToolResultChunk`, `ProgressChunk` (Fanar-specific, bilingual), `DoneChunk`, and
`ErrorChunk`. Consumers need to receive these events in order, pattern-match on their type, and react appropriately
(append tokens, show progress UI, handle errors). We must choose how to expose this to callers.

## Decision

Streaming calls return `Flow.Publisher<StreamEvent>`, where `StreamEvent` is a **sealed interface** with one record
variant per chunk type:

```java
public sealed interface StreamEvent permits TokenChunk, ToolCallChunk, ToolResultChunk,
                                             ProgressChunk, DoneChunk, ErrorChunk {}

Flow.Publisher<StreamEvent> publisher = client.chat().stream(request);
```

Consumers use pattern-matching switch, which the compiler verifies is exhaustive:

```java
switch (event) {
    case TokenChunk t    -> ui.appendToken(t.delta());
    case ProgressChunk p -> ui.showProgress(p.progress().message());
    case ToolCallChunk c -> tools.invoke(c.choices());
    case ToolResultChunk r -> tools.record(r.choices());
    case DoneChunk d     -> ui.complete();
    case ErrorChunk e    -> ui.showError(e.choices());
}
```

A convenience `Stream<StreamEvent> toStream(Flow.Publisher<StreamEvent>)` helper is provided for callers who prefer
iterator-style consumption on a virtual thread (ADR-004):

```java
for (var event : Streams.toStream(client.chat().stream(request))) {
    // ...
}
```

No callback-builder API (`onToken`, `onProgress`, …) is provided at launch.

## Alternatives considered

- **Callback builder** (`.onToken(...).onProgress(...).onDone(...)` fluent API). *Rejected as primary*: grows a
  method for every new chunk type, loses the exhaustiveness guarantee of pattern matching, and gets noisier as
  Fanar's stream protocol evolves. Could be added later as a thin convenience layer if demand arises.
- **Blocking `Stream<StreamEvent>`** only. *Rejected as primary*: loses reactive composability (backpressure,
  cancellation, combinators). Offered as a helper, not as the primary surface.
- **Reactor `Flux<StreamEvent>`** directly. *Rejected*: violates ADR-003 (no third-party types on API).
- **RxJava `Flowable<StreamEvent>`** directly. *Rejected*: same reason.

## Consequences

### Positive
- JDK-native, zero runtime dependencies (JLBP-1, ADR-003).
- Adaptable to any reactive library on the caller's side: `Flux.from(publisher)`, `Flowable.fromPublisher(publisher)`,
  Kotlin's `flow { publisher.collect(...) }`.
- Sealed-interface pattern matching is exhaustive and compiler-verified — if Fanar adds a chunk type, our sealed
  hierarchy grows and every consumer's `switch` breaks at compile time until they handle the new variant.
- `.toStream()` helper gives virtual-thread consumers an iterator-style API without subscribing to a publisher.

### Negative / Trade-offs
- `Flow.Publisher` is less ergonomic than reactive-library types for users not already familiar with it (no built-in
  combinators like `map`, `filter`). Users who want combinators adapt to their reactive library of choice once at
  the call site.
- Adding a new sealed variant is technically a breaking change (forces every `switch` to add a case). Documented in
  JLBP-10 / ADR-019; minor-version changes only, pre-1.0.

### Neutral
- The `StreamEvent` hierarchy lives in `qa.fanar.core.chat` (ADR-011), domain-grouped.

## References

- ADR-001 Java 21 minimum (sealed interfaces, pattern matching)
- ADR-003 Framework-agnostic public API
- ADR-004 Sync-primary API with async sugar
- ADR-017 SSE parsing strategy
- WHATWG Server-Sent Events specification
