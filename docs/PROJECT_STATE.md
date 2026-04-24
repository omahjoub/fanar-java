# Project state

> **Snapshot — 2026-04-24.** Updated when the project crosses a milestone. If this file looks wrong or stale,
> that is the most important signal — update it in the same PR as whatever moved.

## Phase

**Implementation phase — started.** All foundational decisions are captured in ADRs; the first contract-layer
types are on disk (exception hierarchy), quality gates are live on `fanar-core`. Subsequent PRs add the SPIs,
domain DTOs, `FanarClient` facade, and transport internals — in that order.

## What's decided

The shape of the SDK is fully captured in:

- [19 ADRs](adr/INDEX.md) — Java version, scope, API shape, SPIs, transport, JSON, retries, DTOs, package/module layout, stability policy, internals-aren't-a-contract.
- [Compatibility matrix](COMPATIBILITY.md) — the lighthouse: what Fanar offers, what core provides, what downstream adds.
- [API sketch](API_SKETCH.md) — aspirational code shape for every major call. Living document.
- [Library best practices](JAVA_LIBRARY_BEST_PRACTICES.md) — hygiene rules the implementation must respect.
- [Architecture](ARCHITECTURE.md) — Fanar API surface plus our own module layout and request-flow diagrams.

- **Reactor skeleton** — 4 Maven modules (`core`, `json-jackson2`, `json-jackson3`, `bom`) with `module-info.java`.
  `./mvnw verify` passes with two expected warnings (module-name terminal digits, see ADR-001 / ADR-010).
- **Core contract — exception hierarchy** — `qa.fanar.core.FanarException` sealed hierarchy with 13 concrete
  subtypes (one per `ErrorCode`), plus `ErrorCode` and `ContentFilterType` enums. See ADR-006.
- **Core contract — SPIs** — `qa.fanar.core.spi.FanarJsonCodec`, `Interceptor` + nested `Chain`,
  `ObservabilityPlugin`, `ObservationHandle`, and the `FanarObservationAttributes` constants class. Interface
  surfaces only; the only concrete code is the silent no-op observability plugin used as default. See ADR-008,
  ADR-012, ADR-013.
- **Core contract — retry configuration** — `qa.fanar.core.RetryPolicy` record and
  `qa.fanar.core.JitterStrategy` enum. Canonical retryable-exception matrix implemented via
  `RetryPolicy.isDefaultRetryable(FanarException)`, an exhaustive pattern match on the sealed
  exception hierarchy. Configuration only — the retry loop itself lands with the interceptor
  implementation PR. See ADR-014.
- **Core contract — chat message hierarchy** — `qa.fanar.core.chat` package populated with the
  five `Message` role variants (`SystemMessage`, `UserMessage`, `AssistantMessage`,
  `ThinkingMessage`, `ThinkingUserMessage`), dual content-part hierarchies (`UserContentPart`
  with `TextPart` / `ImagePart` / `VideoPart`; `AssistantContentPart` with `TextPart` /
  `RefusalPart`, overlapping at `TextPart`), the `ToolCall` record, and the `ChatModel` /
  `Source` / `ImageDetail` enums. See ADR-005, ADR-015.
- **Core contract — chat request** — `qa.fanar.core.chat.ChatRequest` record with all 31
  components (messages, model, sampling knobs, thinking flag, Fanar-Sadiq / Islamic-RAG scope,
  vLLM-flavored advanced sampling) plus its nested `ChatRequest.Builder` with fluent
  `with*`-style setters. Compact-constructor validation covers required-field nulls, numeric
  range checks, the 4-entry `stop` cap, and defensive copies of every collection / map.
- **Core contract — chat response** — `qa.fanar.core.chat.ChatResponse` and the 14 supporting
  types: `ChatChoice`, output-side `ChatMessage`, `ResponseContent` sealed hierarchy
  (`TextContent` / `ImageContent` / `AudioContent` — distinct from input parts because output
  allows audio and has no image-detail hint), `Reference` (Islamic-RAG citations),
  `FinishReason` enum, `CompletionUsage` + `CompletionTokensDetails` (with
  `reasoning_tokens` for thinking-enabled models) + `PromptTokensDetails`, and the
  `ChoiceLogprobs` / `TokenLogprob` / `TopLogprob` log-probability chain.
- **Core contract — streaming** — `qa.fanar.core.chat.StreamEvent` sealed hierarchy over six
  chunk variants (`TokenChunk`, `ToolCallChunk`, `ToolResultChunk`, `ProgressChunk`,
  `DoneChunk`, `ErrorChunk`), each with its own flattened `Choice*` record (`ChoiceToken`,
  `ChoiceToolCall`, `ChoiceToolResult`, `ChoiceFinal`, `ChoiceError`). Supporting types:
  bilingual `ProgressMessage`, streaming-side `ToolCallData` + `FunctionData` +
  `ToolResultData`. The sealed interface exposes common `id()` / `created()` / `model()`
  accessors so cross-chunk metadata is available without pattern matching.
- **Core contract — `FanarClient` facade** —
  `qa.fanar.core.FanarClient` (final, `AutoCloseable`) with its nested `Builder` covers every
  configuration concern from ADR-016: `apiKey` (String or rotating `Supplier<String>`),
  `baseUrl`, `httpClient`, `jsonCodec`, interceptor chain, `retryPolicy`, observability plugin,
  timeouts, user-agent, default headers. Environment-variable fallbacks for `FANAR_API_KEY` /
  `FANAR_BASE_URL`. `ServiceLoader` discovery for `FanarJsonCodec` (with the standard
  "add fanar-json-jackson…" error). HttpClient ownership tracked (close only what we built).
- **Core internals — transport + real `ChatClient`** —
  `qa.fanar.core.internal.transport.HttpTransport` (functional interface) with
  `DefaultHttpTransport` wrapping the JDK `HttpClient` and translating
  `IOException` / `InterruptedException` into `FanarTransportException` (interrupt flag
  preserved). `InterceptorChainImpl` walks user interceptors and terminates at the transport;
  `BearerTokenInterceptor` adds `Authorization: Bearer …` via a per-call `Supplier<String>`
  so tokens can rotate without rebuilding the client. `ExceptionMapper` translates 4xx / 5xx
  responses into the typed exception hierarchy (ADR-006), honouring `Retry-After` on 429.
  `qa.fanar.core.internal.chat.ChatClientImpl` replaces the old `SkeletonChatClient`: `send`
  runs the chain end-to-end (encode → interceptors → transport → map-or-decode); `sendAsync`
  spawns one virtual thread per call — no executor to manage; `stream` still throws `UOE`
  until the SSE parser lands. Observation attributes (`fanar.model`, `http.method`,
  `http.url`, `http.status_code`) surface on every call; exceptions are reported via
  `ObservationHandle.error(...)` before being rethrown.
- **397 tests, 100 % JaCoCo coverage** across 75 bytecode-bearing classes in `fanar-core`.
- **Quality gates on `fanar-core`** — JaCoCo `check` enforces 100 % on instruction / line / branch / method /
  complexity; `dependency:analyze` fails on undeclared or unused direct deps; Javadoc doclint runs at javac time.
  Adapter modules stay in skeleton mode (`jacoco.skip=true`) until they carry real code.
- **Library-first dependency hygiene** — the reactor parent no longer imports Spring Boot's BOM; versions come
  from `junit-bom` (tests) and explicit pins. No implicit transitives from framework BOMs.
- **CI** — build matrix (Java 21 and 25), link-check for every doc, dependency hygiene gates via `mvn verify`,
  zero published artifacts yet.
- **`.github/`** — PR template with scope-split checklist, issue templates, SECURITY, CODEOWNERS, dependabot
  (Maven + GitHub Actions). All consistent with the design.

Streaming, retries, and the JSON adapters are the remaining gaps on the path to a usable SDK.

## What's next

In the order we plan to tackle them — each one its own focused PR:

1. **SSE parser** under `core.internal.sse`. Parses server-sent `data:` frames, dispatches by
   shape into the right `StreamEvent` subtype, and turns the JDK `Flow.Publisher<String>` from
   `BodyHandlers.ofLines()` into `Flow.Publisher<StreamEvent>`. Replaces the remaining
   `UnsupportedOperationException` in `ChatClientImpl.stream(...)`.
2. **Retry interceptor** — concrete `Interceptor` under `core.internal.retry` that consumes the
   already-implemented `RetryPolicy` record (exponential + full-jitter back-off, retryable
   exception matrix, `Retry-After` honouring on 429). Bearer-token interceptor is already in.
3. **Jackson 3 adapter** — `Jackson3FanarJsonCodec`, `ServiceLoader` descriptor, reachability metadata.
4. **Jackson 2 adapter** — mirror of the Jackson 3 adapter against the `com.fasterxml.jackson.*`
   package family.
5. **GraalVM reachability metadata + native-image smoke test** in CI (ADR-009).

The [API sketch](API_SKETCH.md) shows the target; the [ADRs](adr/INDEX.md) justify the choices.

## Cadence for updates

Update this file when:

- A phase completes (design → implementation → testing → release).
- A significant milestone ships (first DTO, first passing integration test, first tagged release, first framework adapter).
- An ADR gets superseded.

Commit the update in the same PR as the change that motivated it — not separately.
