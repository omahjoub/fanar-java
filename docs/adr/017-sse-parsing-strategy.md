# ADR-017 — SSE parsing strategy

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

Fanar's `/v1/chat/completions` endpoint streams Server-Sent Events (`text/event-stream`) carrying a typed union of
chunk shapes (`TokenChunk`, `ToolCallChunk`, `ToolResultChunk`, `ProgressChunk`, `DoneChunk`, `ErrorChunk`). The SDK
must parse that stream reliably — handling partial frames, UTF-8 boundary splits across network buffers, mid-event
reconnection semantics, and malformed payloads — and dispatch each event's JSON payload through `FanarJsonCodec`
(ADR-008) into the corresponding `StreamEvent` subtype (ADR-005).

Parsing strategy choice affects our dependency footprint, the ergonomics of our streaming surface, and our
native-image story.

## Decision

Hand-rolled SSE parser reading the response body as an `InputStream`, living **entirely in
`qa.fanar.core.internal.sse`** (non-exported per ADR-018).

The parser pipeline:

1. **Line stream** — the transport hands back `HttpResponse<InputStream>` from a *synchronous*
   `send`, the same call the non-streaming path makes (ADR-004, ADR-007); a virtual thread reads it
   line by line. One transport method serves both shapes, and the interceptor chain does not have to
   know which one it is carrying.
2. **Frame accumulator** — lines are collected into SSE frames. Lines starting with `data:`
   accumulate the payload; a blank line dispatches the frame. **Every other field is ignored** —
   `event:`, `id:`, `retry:` and comment lines alike. Fanar sends a single unnamed event type, so a
   frame is its `data` and nothing else; parsing fields we would only discard is surface without
   purpose. Ignoring rather than rejecting them is deliberate: an unknown field must never break a
   stream.
3. **JSON decode** — the `data:` payload is handed to `FanarJsonCodec` (ADR-008), which deserializes into the
   appropriate `StreamEvent` subtype (discriminated by shape: `progress` field → `ProgressChunk`, `choices[*].delta`
   shape → `TokenChunk` / `ToolCallChunk` / `ToolResultChunk`, presence of `usage` → `DoneChunk`, presence of error →
   `ErrorChunk`).
4. **Emission** — decoded events are published to the caller's `Flow.Publisher<StreamEvent>` (ADR-005) as they arrive.

All parsing logic, all state machines, all error handling live under `qa.fanar.core.internal.sse`. The public surface
exposes only `Flow.Publisher<StreamEvent>`. If a future release changes strategy — to byte-level reactive, to a
library, to a third-party parser — no downstream module notices, guaranteed by ADR-018.

## Alternatives considered

- **Dedicated SSE library** (e.g., LaunchDarkly's OkHttp eventsource, Sonatype's sseclient). *Rejected*: adds a
  runtime dependency for approximately 30 lines of parsing logic. Most candidates transitively drag OkHttp or Netty,
  which pollute the classpath, inflate the native-image metadata footprint (ADR-009), and contradict our zero-deps
  posture (ADR-002, JLBP-1).
- **Byte-level reactive via `BodyHandlers.ofPublisher()`**. Returns `Flow.Publisher<List<ByteBuffer>>` — maximum
  control over back-pressure and buffering. *Rejected*: more complex than the Fanar stream needs, and
  it would make streaming the one path that does not go through the shared synchronous transport.
  Reading an `InputStream` on a virtual thread gets the same back-pressure for far less machinery,
  because the read simply blocks until the subscriber asks for more. ADR-018 guarantees we can
  refactor to this later, fully internally, if the characteristics ever matter.
- **Custom Netty-based streaming**. *Rejected*: massive over-engineering for a client SDK; violates sync-primary
  stance (ADR-004); would force a Netty runtime dep on every consumer.

## Consequences

### Positive
- Zero runtime dependencies (JLBP-1, ADR-002).
- The SSE wire format is small and stable — it has not changed in the 15 years since its specification — so hand-
  rolling is low-risk.
- JDK-native path is first-class in GraalVM native-image (ADR-009); no reflection, no special metadata.
- Refactoring to a different strategy (byte-level, library-based, custom selector loop) is a fully internal change
  under ADR-018 — downstream modules never break.

### Negative / Trade-offs
- We own the parser's correctness. The tests cover partial frames across network boundaries, CR/LF
  handling, comment lines, unknown fields, malformed `data:` payloads and half-written JSON.
  UTF-8 continuation bytes split across reads are handled by the JDK rather than by us — the reader
  decodes, so a multi-byte character split across two network buffers never reaches our state
  machine.
- A subtle category of bugs (e.g., off-by-one in line-boundary detection, mishandling of mid-UTF-8 byte splits) is
  ours to avoid. Mitigated by a comprehensive test suite with curated fixtures and property-based tests.

### Neutral
- The parser does not implement the `Last-Event-ID` reconnect semantics of the full SSE specification — Fanar does
  not require it, and the streaming surface (ADR-005) delegates reconnection semantics to the user.
  `retry:` is ignored for the same reason: it is advice about reconnecting, and we do not reconnect.
- The publisher owns the call's observation for the life of the stream and closes it on the terminal
  signal (ADR-013), so a stream that dies mid-flight is reported as a failure rather than as the
  clean success its handshake was.

## Proved by

- `SseFrameAssemblerTest` — the frame state machine: multi-line `data:`, blank-line dispatch,
  comments, unknown fields, CRLF.
- `SseStreamPublisherTest` — demand, cancellation, error propagation and the observation lifecycle.
- `FanarClientRetryIntegrationTest.connectionDropMidStreamIsNotRetried` — the whole path through
  `FanarClient.builder()` against a server that drops mid-stream.

## References

- ADR-005 Streaming via `Flow.Publisher`
- ADR-008 JSON as an SPI with two Jackson adapters
- ADR-011 Package conventions (`.internal.sse`)
- ADR-018 Internals are not a contract
- WHATWG Server-Sent Events specification
