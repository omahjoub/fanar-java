# ADR-023 — Streaming TTS via `Flow.Publisher<byte[]>`

- **Status**: Accepted
- **Date**: 2026-08-06
- **Deciders**: @omahjoub

## Context

The 2026-08 Fanar spec added a `stream` flag to `POST /v1/audio/speech`: when `true`, the server
delivers the synthesized audio (mp3 or wav) chunked as it is generated, instead of buffering the
whole clip. The SDK must expose this without breaking the buffered `speech(...) → byte[]` path.

Constraints already in place:

- ADR-005 pinned `java.util.concurrent.Flow` as the streaming idiom (chat SSE:
  `Flow.Publisher<StreamEvent>`), with JDK-only types on the public surface (ADR-003).
- The transport (ADR-007) already hands every domain client a lazy
  `HttpResponse<InputStream>` (`BodyHandlers.ofInputStream()`); the buffered path simply drains
  it eagerly. Streaming is therefore a new consumption surface, not transport work.
- Chat deliberately does not model the wire field `stream` on `ChatRequest` — the call-site
  method (`send` vs `stream`) decides, and the transport splices `"stream":true` into the
  serialized body. `TextToSpeechRequest` mirrors that posture.
- Spring AI's `TextToSpeechModel` extends `StreamingTextToSpeechModel`; before this ADR our
  adapter faked `stream(...)` by wrapping the one-shot result in a single-element `Flux`.

## Decision

1. **`AudioClient` gains `Flow.Publisher<byte[]> speechStream(TextToSpeechRequest)`.** The
   request record stays free of a `stream` component; the implementation splices
   `"stream":true` into the encoded body via the shared internal `StreamFlag` helper (extracted
   from the chat implementation) and hands the response body to a new internal
   `AudioStreamPublisher`.
2. **Chunks are opaque `byte[]`.** Boundaries follow transport reads (8 KiB buffer) and carry
   no semantic meaning; subscribers concatenate chunks in emission order to reconstruct the
   clip. No container-aware framing — the SDK does not parse mp3 frames or wav blocks.
3. **`AudioStreamPublisher` is the structural twin of `SseStreamPublisher`**: single
   subscriber, demand-gated emission on a virtual thread, cancel closes the connection,
   `InterruptedException` wrapped in `FanarTransportException`, other failures passed to
   `onError` as-is. Interceptors and error mapping apply to the initial exchange only, exactly
   like chat streaming.
4. **The Spring AI adapter streams for real**: `FanarTextToSpeechModel.stream(...)` bridges the
   publisher with `JdkFlowAdapter.flowPublisherToFlux` (the same idiom `FanarChatModel` uses)
   and emits one `TextToSpeechResponse` per chunk.

## Alternatives considered

- **`InputStream` return** (`speechStream` → `InputStream`). *Rejected*: simple, but breaks the
  ADR-005 idiom, offers no back-pressure contract, and adapts worse to reactive consumers
  (Spring AI's `Flux`, RxJava) — the main audience for streaming TTS.
- **Modelling `stream` as a `TextToSpeechRequest` component.** *Rejected*: invites the invalid
  `stream=true` + `speech()` combination and contradicts the chat precedent; the return type,
  not a request flag, is the honest signal of delivery mode.
- **A typed chunk event (e.g. `AudioChunk` record with index/offset).** *Rejected*: the wire
  provides no chunk metadata to model — wrapping `byte[]` in a record adds allocation and API
  surface for zero information.
- **Deferring streaming to 0.3.0.** *Rejected*: the transport already supports it, the Spring AI
  streaming surface exists and was semantically a lie, and 0.2.0's goal is full spec parity.

## Consequences

### Positive
- Full spec parity for TTS delivery modes; time-to-first-audio drops for long inputs.
- `FanarTextToSpeechModel.stream(...)` honours its interface contract instead of faking it.
- Back-pressure and cancellation semantics match chat streaming — one mental model.

### Negative / Trade-offs
- Chunk boundaries are transport artifacts; players that need whole containers must buffer
  anyway. Documented on the method.
- A second publisher implementation to maintain — mitigated by keeping it a line-for-line twin
  of the SSE one minus frame assembly.

### Neutral
- Observability reuses the `fanar.audio.speech` operation name (chat streaming reuses
  `fanar.chat` the same way); the initial exchange is observed, mid-stream reads are not.

## References

- ADR-005 Streaming via `Flow.Publisher`
- ADR-007 JDK `HttpClient` as the default transport
- ADR-012 Interceptor SPI (handshake-only application to streams)
- ADR-021 Spring AI 2.0 adapter
- OpenAPI spec § `TextToSpeechRequest.stream`
