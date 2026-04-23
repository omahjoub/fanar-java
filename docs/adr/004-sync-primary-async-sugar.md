# ADR-004 — Sync-primary API with async sugar

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

Every HTTP-based SDK must expose sync, async, or both. Historically, the choice favored async-primary designs
(`CompletableFuture`, reactive streams) because sync I/O blocked platform threads and limited scalability. With
Java 21 virtual threads (JEP 444), that calculus has shifted: a blocking call on a virtual thread waits without
occupying a carrier thread, so "sync" code scales as well as async for I/O-bound workloads.

A library that aligns with this post-Loom reality can be simpler to use (no `CompletableFuture` spaghetti), cheaper
at the call site (no future-machinery overhead for callers who don't compose), and still compose asynchronously when
needed.

## Decision

The SDK's primary API is **synchronous**:

```java
ChatResponse response = client.chat().send(request);
```

Async is thin sugar implemented by dispatching the sync call to a virtual-thread executor:

```java
CompletableFuture<ChatResponse> future = client.chat().sendAsync(request);
// internally: CompletableFuture.supplyAsync(() -> send(request), virtualThreadExecutor)
```

Streaming is a third, genuinely non-blocking shape via `Flow.Publisher<StreamEvent>` (ADR-005).

**Implementation discipline**: every sync code path must be virtual-thread-friendly — no `synchronized` blocks on hot
paths, no `ThreadLocal` abuse, no calls that would pin a virtual thread to its carrier.

## Alternatives considered

- **Async primary with sync as `async.join()`**. *Rejected*: anachronistic in a virtual-threads world. Callers who
  want sync pay `CompletableFuture` machinery for zero scaling benefit.
- **Twin implementations** where sync and async each have their own HTTP call path. *Rejected*: the "each path is
  honest" argument weakens when sync-on-vthread *is* the honest path. Twin implementations double the code surface
  for no user benefit.
- **Reactive-only** (`Mono<ChatResponse>`). *Rejected*: violates ADR-003 (no third-party types on API) and forces
  the reactive style on everyone.

## Consequences

### Positive
- Simple, idiomatic Java at the call site.
- Scales natively on virtual threads without caller ceremony.
- Async composition (`CompletableFuture.thenApply`, etc.) is available when needed, not required.
- `CompletableFuture<T>` adapts trivially to any reactive / coroutine library on the caller's side.

### Negative / Trade-offs
- Internal code must be written with virtual-thread compatibility in mind. No `synchronized` on hot paths is a real
  discipline — reviewable via code review and occasionally verifiable with `jcmd Thread.print | grep pinned`.
- Users deeply committed to reactive stacks may find `CompletableFuture` less ergonomic than `Mono`/`Flux`; they
  convert via `Mono.fromFuture` at the call site.

### Neutral
- The sync/async split does not apply to streaming (ADR-005), which has its own shape.

## References

- ADR-001 Java 21 minimum (enables virtual threads)
- ADR-005 Streaming via `Flow.Publisher`
- ADR-016 `FanarClient` builder and domain facades
- JEP 444 — Virtual Threads
- Ron Pressler et al., "Writing concurrent applications after Loom"
