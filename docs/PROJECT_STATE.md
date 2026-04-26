# Project state

> **Snapshot — 2026-04-25.** Updated when the project crosses a milestone. If this file looks wrong or stale,
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
  spawns one virtual thread per call — no executor to manage. Observation attributes
  (`fanar.model`, `http.method`, `http.url`, `http.status_code`) surface on every call;
  exceptions are reported via `ObservationHandle.error(...)` before being rethrown.
- **Core internals — SSE parser + real `chat().stream(...)`** — `qa.fanar.core.internal.sse`:
  `SseFrameAssembler` implements the WHATWG SSE field-by-field parsing (comments, unknown
  fields, CR/LF normalisation, blank-line dispatch), `StreamEventDecoder` routes each
  `data:` payload into one of the six `StreamEvent` subtypes by shape (ADR-017) using a
  two-pass decode through `FanarJsonCodec` (map-inspect, then typed decode), and
  `SseStreamPublisher` implements a `Flow.Publisher<StreamEvent>` with a single-subscriber
  guard, virtual-thread producer, bounded-demand back-pressure, subscription cancellation,
  and best-effort close of the HTTP body. `ChatClientImpl.stream` runs the same interceptor
  pipeline as `send` but with `Accept: text/event-stream` and `"stream": true` injected into
  the JSON body, then wraps the response body in an `SseStreamPublisher`.
- **Core internals — retry interceptor** — `qa.fanar.core.internal.retry.RetryInterceptor`
  sits at the head of the interceptor chain (outside the bearer-token interceptor, so every
  retry re-signs the request). Honours the caller's `RetryPolicy`: exponential back-off
  with the configured `JitterStrategy` (NONE / FULL / EQUAL), `maxAttempts` cap, and the
  retryable-exception matrix. `Retry-After` on `FanarRateLimitException` overrides the
  back-off curve for that attempt. Emits one `retry_attempt` observation event per retry
  and records the final count in `fanar.retry_count`. `Sleeper` + `RandomGenerator` are
  injectable for deterministic tests.
- **455 tests, 100 % JaCoCo coverage** across 83 bytecode-bearing classes in `fanar-core`.
- **`fanar-json-jackson3` — Jackson 3 adapter** — `Jackson3FanarJsonCodec` backed by a
  `tools.jackson.databind.json.JsonMapper` pre-configured for the Fanar wire format
  (snake-case naming, `NON_NULL` inclusion, unknown-property tolerance, no default enum
  serialization — enums use `wireValue()` via a dedicated module). Six `StdDeserializer`
  flatten `delta.content`, `delta.tool_calls`, `delta.tool_result`, `delta.references`, and
  `progress.message` one level so the core records stay annotation-free. `ServiceLoader`
  descriptor + JPMS `provides ... with ...` directive. GraalVM reachability metadata for
  adapter-specific reflection targets. 31 tests, 100 % JaCoCo on 11 bytecode-bearing classes.
  `tools.jackson:jackson-bom:3.0.0` pinned in the reactor parent; every Jackson dep is
  {@code provided} scope so the consuming application brings the runtime.
- **`fanar-json-jackson2` — Jackson 2 adapter** — `Jackson2FanarJsonCodec` backed by a
  `com.fasterxml.jackson.databind.ObjectMapper` with the same behavioural contract as the
  Jackson 3 adapter: snake-case, `NON_NULL`, unknown-property tolerance, flattening module
  for the five `Choice*` records + `ProgressChunk`, wire-value enum module for the four chat
  enums. Catches `IOException` broadly since Jackson 2's `JacksonException` extends
  `IOException`. Ships the same `ServiceLoader` descriptor + reachability metadata layout as
  the Jackson 3 adapter. 36 tests, 100 % JaCoCo on 11 bytecode-bearing classes.
  `com.fasterxml.jackson:jackson-bom:2.20.0` pinned in the reactor parent. For Spring Boot
  3.x consumers.
- **Quality gates on `fanar-core`** — JaCoCo `check` enforces 100 % on instruction / line / branch / method /
  complexity; `dependency:analyze` fails on undeclared or unused direct deps; Javadoc doclint runs at javac time.
  Adapter modules stay in skeleton mode (`jacoco.skip=true`) until they carry real code.
- **`fanar-java-e2e` — live battle-test module** — single Maven module (one classpath island
  per Jackson 2 + Jackson 3) auto-detected by IDEs, never published, JaCoCo and
  `dependency:analyze` disabled. Test infrastructure (`Probes`, `TestClients`,
  `LoggingInterceptor`, `CapturingInterceptor`) and the live suite live entirely under
  `src/test`. `LiveChatCompletionsTest` is parameterized over both codecs (19 cases × 2 codecs
  = 38 live calls covering the five non-vision models, multi-turn / thinking / Sadiq RAG
  conversation shapes including a typed-`BookName` constraint, sampling determinism / `n` /
  stop / logprobs, streaming token sequence / sync-vs-stream parity / Sadiq progress chunks /
  Sadiq tool-call telemetry / cancel, and 401 error mapping). `AdapterParityTest` adds three
  offline parity checks plus one live parity check that captures real wire bytes via a
  `CapturingInterceptor` and decodes them through both adapters. Future framework adapters
  (Spring Boot 3 / 4, Quarkus, GraalVM, LangChain4j) will live in sibling modules — splitting
  only when classpath isolation forces it, never per-adapter for cosmetic reasons.
- **Core hardening — wire-format findings folded into the records** — three additions
  surfaced from the live battle-test, none documented in the OpenAPI spec but consistently
  emitted by the real server:
  - `ChatChoice.stopReason` (nullable `String`) — captures Fanar's `<end_of_turn>`-style raw
    stop token alongside the normalized `FinishReason`.
  - `CompletionUsage.successfulRequests` + `totalCost` — Sadiq-only retrieval-pipeline
    accounting, both nullable on non-Sadiq responses.
- **Open value-class records for Fanar-controlled identifiers** — `ChatModel`, `Source`,
  `FinishReason`, `ImageDetail`, `ContentFilterType`, and `BookName` are all
  `record(String wireValue)` value classes with public constants for known wire values plus a
  permissive `of(String)` factory. Consumers are never blocked by SDK release cadence: when
  Fanar adds a model, source, finish reason, or book, callers reach it via `of(...)` the same
  day, and unknown response values decode without failing. Each type exposes a `KNOWN`
  `Set<...>` of bundled constants for IDE autocomplete and catalogue iteration. `BookName`
  carries 572 inline `KNOWN` entries from `BookNamesEnum` — no resource file. `ErrorCode`
  and `JitterStrategy` stay enums because their values map 1:1 to exception subtypes /
  library behaviour and adding a new one is genuinely an SDK release event. Both adapters
  share a generic `WireValueModule` (renamed from `WireValueEnumModule`) that registers
  (de)serializers via `wireValue()` / `of(String)`.
- **Library-first dependency hygiene** — the reactor parent no longer imports Spring Boot's BOM; versions come
  from `junit-bom` (tests) and explicit pins. No implicit transitives from framework BOMs.
- **CI** — build matrix (Java 21 and 25), link-check for every doc, dependency hygiene gates via `mvn verify`,
  zero published artifacts yet.
- **`.github/`** — PR template with scope-split checklist, issue templates, SECURITY, CODEOWNERS, dependabot
  (Maven + GitHub Actions). All consistent with the design.

## What's next

**Phase 1 — done.** The `fanar-java-e2e` module is in place, the chat surface has been
exercised end-to-end against the real Fanar API across both codec adapters, and every
wire-format quirk the live tests surfaced has been folded back into the records (see
`ChatChoice.stopReason`, `CompletionUsage.successfulRequests` / `totalCost`, `BookName`).
Two upstream gaps are documented rather than worked around: Fanar silently ignores the
`stop` parameter on chat completions, and the request schema does not accept user-defined
tools — both captured in project memory and in test docstrings.

**Phase 2a — done.** Every remaining Fanar domain has a typed client, DTOs, offline tests,
and a parameterized live test running across both codec adapters: `models`, `tokens`,
`moderations`, `translations`, `poems`, `images`, and `audio` (voices CRUD + TTS speech +
STT transcriptions, the latter as a sealed `text` / `srt` / `json` response). `FanarClient`
exposes one accessor per domain (`client.audio()`, `.images()`, etc.). All upstream quirks
discovered on the way are captured in project memory (Diwan 504s, audio voices auth
gating, audio rate-limiting under chained TTS+STT) rather than papered over in tests.

**Phase 2b — done.** All three planned `ObservabilityPlugin` adapters shipped as separate
modules with `provided`-scope dependencies and zero runtime cost in core: `obs-slf4j`
(structured log lines), `obs-otel` (spans + W3C `traceparent` propagation),
`obs-micrometer` (Micrometer Observation API → metrics / tracing through the user's wired
handlers). A `compose(plugin...)` factory was added to {@code ObservabilityPlugin} so users
can wire any combination at once via a single slot. The wire-logging path was also pulled
out of e2e into a published `interceptor-logging` module (`WireLoggingInterceptor` with
OkHttp-style level ladder, SLF4J sink, header redaction, body byte cap, streaming-aware).
Observability adapters are <em>opt-in</em> by design — none ship a {@code ServiceLoader}
descriptor, so adding the jar to the classpath does not silently change the
{@code FanarClient} default of {@link qa.fanar.core.spi.ObservabilityPlugin#noop()}.

**Phase 2c — broaden once the core is proven.**

1. **Nightly CI for live e2e** — one scheduled GitHub Actions job runs the live suite with
   `FANAR_API_KEY` injected as a secret; PR builds stay offline-only.
2. **Framework adapter modules** — Spring Boot 3 / Spring Boot 4 / Quarkus / LangChain4j
   smoke tests live under `e2e-spring-boot-3/`, `e2e-spring-boot-4/`, etc. — sibling Maven
   modules added only when classpath isolation forces it (e.g. Spring 6 vs 7, jakarta
   namespace conflicts, GraalVM native-image build).
3. **v0.1.0 release** — Maven Central publication pipeline, a full README, the SDK
   versioning policy from ADR-019 flipped on, and the pre-1.0 stability guarantees from
   JLBP applied.

The [API sketch](API_SKETCH.md) shows the target; the [ADRs](adr/INDEX.md) justify the choices.

## Cadence for updates

Update this file when:

- A phase completes (design → implementation → testing → release).
- A significant milestone ships (first DTO, first passing integration test, first tagged release, first framework adapter).
- An ADR gets superseded.

Commit the update in the same PR as the change that motivated it — not separately.
