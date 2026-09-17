# ADR-030 — Google ADK Java adapter

- **Status**: Proposed
- **Date**: 2026-09-17
- **Deciders**: @omahjoub

## Context

Google's Agent Development Kit (ADK) is the agent framework behind Vertex AI Agent Engine, with a
Java implementation (`com.google.adk:google-adk`) and a dev server that discovers agents from
compiled classes and serves a chat UI. Its model SPI is `BaseLlm`: two abstract methods,
`generateContent(LlmRequest, boolean stream)` returning an RxJava `Flowable<LlmResponse>`, and
`connect(LlmRequest)` for bidirectional sessions. Wiring a provider into it needs no cooperation
from the provider, so the question this record answers is what wiring one costs.

An adapter was written by hand against `fanar-core` 0.6.0 and ADK 1.9.0 (1.10.0 did not exist on
Maven Central on the date above). The ADK behaviour cited below is read from ADK's sources, not its
documentation, because the streaming protocol is enforced by `BaseLlmFlow` and declared nowhere.
What the adapter cost, in order:

- **The agent did not appear in the dev UI, and the log said nothing usable.**
  `FanarClient.builder().build()` threw because no `FanarJsonCodec` was on the classpath — the
  `IllegalStateException` of ADR-016. ADK's `CompiledAgentLoader` reads the agent's public static
  `ROOT_AGENT` field reflectively, which runs the class initialiser, so the failure arrived as
  `ExceptionInInitializerError` — a `LinkageError` whose `getMessage()` is `null` — and the loader
  logs the message, not the throwable: `Linkage error loading class … : null`, then
  `Found 0 total agents`. Recovering the actionable text meant loading the class under a
  `URLClassLoader` by hand.
- **`.apiKey("FANAR_API_KEY")` passed the literal string** as the bearer token, and the first
  symptom was a 401. The builder's env fallback for that variable (`FanarClient.ENV_API_KEY`)
  already exists and was not found.
- **`0.7` went on the wire as `0.699999988079071`.** ADK's `GenerateContentConfig.temperature()`
  is `Optional<Float>`; `ChatRequest.temperature` is `Double`, and widening a `float` preserves
  its binary value, not its decimal one.
- **The streaming contract is not in the signature.** ADK expects `partial(true)` deltas followed
  by exactly one non-partial response carrying the aggregated turn. `Event.finalResponse()` is
  false for a partial event and `BaseLlmFlow` ends its loop only on a final one, so a stream whose
  last emission is partial re-invokes the model until `RunConfig.maxLlmCalls` is exhausted.
  `Runner` never persists partial events, so each re-invocation sends the same prompt again with
  no trace of the text already streamed.
- **A response `Content` whose role is missing or empty is dropped from history** by
  `Contents.isEmptyContent` on the next turn, silently. ADK's own models set `role("model")`.
- **An `LlmResponse` carrying `errorMessage` but neither content nor `errorCode` produces no
  event**, and a step with no events ends the run: the caller sees a completed run with zero
  events and no error.

All but the first are silent, none is discoverable from `BaseLlm`'s signature, and each is one-time
knowledge every ADK user of Fanar would rediscover. The second is a user error the builder's env
fallback already answers; it is listed as cost, and no decision below acts on it.

Two ADK facts shape the design rather than the failure list. ADK builds the `call_llm` span itself
in `BaseLlmFlow`, derives its attributes from `LlmRequest` and `LlmResponse` — token counts and the
finish reason as attributes, the whole response, model version included, as one serialised
attribute — makes the span current for the whole subscription, and no model in ADK emits
telemetry of its own. And
`LlmResponse.customMetadata` exists but `BaseLlmFlow` does not copy it onto the event, so nothing
placed there reaches sessions or the UI.

Three open questions, decided below:

1. **Layering.** ADR-021 put `fanar-spring-ai-starter` on top of `fanar-spring-boot-4-starter`,
   which is also where its runtime JSON codec comes from. ADK is not Spring, so neither half of
   that choice can be copied as-is.
2. **Features Fanar cannot honour.** ADK's runner advances on function calls, and it parses the
   reply as JSON when an agent declares an output schema. Fanar's chat endpoint accepts `tools`
   and silently ignores them ([wire ledger](../WIRE_OBSERVATIONS.md#chat-completions--post-v1chatcompletions),
   2026-09-15) and has no `response_format`. ADR-021 chose silent degradation for Spring AI;
   whether that transfers is the central question of this record.
3. **Ordering.** `PROJECT_STATE.md`, `COMPATIBILITY.md` and `API_SKETCH.md` name LangChain4j as
   the next non-Spring adapter. This record puts ADK ahead of it.

## Decision

- **Ship `fanar-adk`**: reactor directory `adk`, package `qa.fanar.adk`, `Automatic-Module-Name:
  qa.fanar.adk`. The name follows the planned `fanar-langchain4j`: the framework's name, no
  vendor, and no `-starter`, which in this repo means Spring Boot auto-configuration (ADR-020).
  No `module-info.java`: ADK, google-genai and RxJava run on the classpath and declare no JPMS
  modules, the classpath posture ADR-020 records for the Spring starters. Filename derivation
  would yield `fanar.adk`, so the manifest entry exists to keep the module name on the package
  root (ADR-011), not to make the jar loadable.

- **Layer on `fanar-core` directly, with `fanar-json-jackson2` at runtime scope.** ADR-021's
  compile-scope dependency on the SB4 starter does not transfer: an ADK consumer is not a Spring
  consumer. Without the starter nothing supplies a codec, and copying the Spring AI module's
  test-scope codec would ship the first failure above as the default experience. ADK depends on
  Jackson 2 at compile scope, so Jackson 2 is on every ADK classpath already; the adapter therefore
  declares the Jackson 2 codec at runtime scope, as the SB4 starter declares the codec matching its
  framework's Jackson major (ADR-020), and `FanarClient.builder()` discovers it through
  `ServiceLoader` (ADR-008). ADR-020's reservation about `ServiceLoader` concerns AOT processing
  and module-path class loaders; neither is in play on a plain classpath. What skipping the Spring
  layer gives up is accepted: no `fanar.*` property binding, no auto-configuration, no
  `ApplicationContextRunner` entry point. A Spring Boot starter for ADK remains possible later.

- **`FanarLlm extends BaseLlm`, constructed from a `FanarClient` or a `Supplier<FanarClient>`, a
  `ChatModel`, and an optional `FanarLlmOptions`.** The client-plus-model form mirrors
  `FanarChatModel`. The supplier form exists because of the loader failure above: the client is
  built on the first request instead of in the agent's static initialiser, so a missing key or
  codec surfaces as a model error with its message intact rather than as an unnamed `LinkageError`.
  The supplier is memoised with JDK types. Transport, auth, retry, interceptors and observability
  stay configured on `FanarClient.builder()`, where the existing records put them; ADK's own models
  take credentials and build clients internally, and this record does not follow that because it
  would duplicate a configuration surface core already owns. `FanarLlm.model()` returns the Fanar
  wire id, which ADK's `Basic` processor copies into `LlmRequest.model()` and the
  `gen_ai.request.model` attribute.

- **Fanar-only knobs are model-instance-scoped, in a typed `FanarLlmOptions` value.** ADR-024's
  per-call idiom works because Spring AI's `ChatOptions` is a subclassable portable interface with
  a `mutate()` pipeline; ADK's request configuration is `com.google.genai.types.GenerateContentConfig`,
  a concrete Google type with no extension seam, so there is no per-call surface to extend.
  ADR-024's rejection of a generic map stands here for the same reasons — undiscoverable, untyped,
  bypasses `ChatRequest` validation. `FanarLlmOptions` carries the Fanar-only set `FanarChatOptions`
  exposes, plus the policy below, and like `FanarChatOptions` it is a builder-built class rather
  than a record, so a knob Fanar adds later is an added setter, not a changed public constructor. Per-agent variation is one `FanarLlm` per agent, which ADK
  supports natively because `LlmAgent.Builder.model(BaseLlm)` takes an instance.

- **Request mapping.** The system instruction ADK assembles becomes one `SystemMessage`. Each
  `Content` becomes an `AssistantMessage` when its role is `model` and a `UserMessage` otherwise,
  with its text parts concatenated. A `fileData` part with an image or video MIME type becomes an
  `ImagePart` or `VideoPart` by URL, the wire shape `COMPATIBILITY.md` lists as supported.
  `GenerateContentConfig` fields with a `ChatRequest` counterpart are forwarded; the `Float`-typed
  ones are converted through their decimal string so `0.7f` arrives as `0.7`. `candidateCount` is
  not, because ADK's `LlmResponse` carries one candidate; fields with no counterpart are not
  forwarded either, and the module's Javadoc, not this record, holds that list.

- **Features Fanar cannot honour are governed by one policy, `UnsupportedFeaturePolicy`, resolved
  per model instance, default `REJECT`.** The features are function declarations (whether the
  agent declared them, ADK injected them, or a built-in tool carries none), structured output (an
  output schema or a non-text response MIME type, which `LlmAgent.outputSchema` sets), and request
  parts with no Fanar mapping; the exact set is `UnsupportedFeature.Kind` plus the cases in
  `RequestMapperTest`. Under `REJECT` the adapter surfaces an `UnsupportedFeatureException`, whose
  `features()` carry the kind and the item, before anything goes on the wire. Under `IGNORE`, the
  opt-in, they are dropped and the request proceeds — unless nothing sendable is left, which is
  refused under either policy. This is a different
  choice from ADR-021, which governs the Spring AI module only, because the two frameworks fail
  differently. In Spring AI, tool callbacks are one opt-in advisor; dropping them costs that
  feature. In ADK, the runner's loop *is* function calling, and ADK injects tools the user never
  declared: `AgentTransfer` adds a `transfer_to_agent` tool whenever an agent has transfer targets.
  Under silent degradation a multi-agent setup returns 200 with a plain text reply and never
  transfers, which is indistinguishable from a model choosing not to delegate; with an output
  schema, the flow parses prose as JSON. A named policy rather than a boolean keeps a third,
  forwarding mode an added constant; after 1.0.0 an added constant is a minor-version event, which
  is the intended cost.

- **`UnsupportedFeatureException` is a final class in `qa.fanar.adk` rooted in
  `UnsupportedOperationException`. It is not a `FanarException`.** The hierarchy of ADR-006 is
  sealed and core is a named module, so nothing outside core can extend it, and it has no pre-wire
  member: core signals pre-wire problems with plain JDK exceptions. The adapter does the same with
  one JDK-rooted subclass, of `UnsupportedOperationException` rather than core's
  `IllegalArgumentException`, because the request is well-formed and asks for a capability this
  model lacks; `features()` carries the kind and the item so a caller can branch without parsing. A consumer catching `FanarException` at a boundary will not catch it, and
  that is the correct reading: it is a request the SDK refused to send, not a wire error.

- **Multi-agent recipes rather than a multi-agent ban.** `AgentTransfer` injects nothing when the
  parent is a workflow agent (sequential, parallel, loop), or when an agent with no sub-agents
  disallows transfer to both parent and peers; in the latter case ADK's `Runner` routes the next
  user turn back to the root agent. A Fanar agent therefore works as a workflow step or as a leaf
  specialist under a root whose model can call tools — Gemini or Claude, not Fanar, which cannot
  emit `transfer_to_agent` — without opting into `IGNORE`. In both recipes ADK narrates earlier
  agents' replies to the next agent as user-role text, so Fanar receives consecutive user
  messages; the ledger records no observation of that shape yet. Both recipes ship with the
  module's documentation.

- **`LlmRegistry` registration is opt-in, by a static method on `FanarLlm`, under the pattern
  `fanar/.*`.** ADK's registry resolves string model names by regular expression through a factory
  whose only input is the name, and caches one instance per name for the life of the JVM, shared
  by every agent using that name. Registration is therefore an explicit call closing over a
  caller-supplied client supplier and options, never a static side effect of loading our classes,
  and `FanarLlm` holds no per-request state. The factory strips the prefix and passes the
  remainder to `ChatModel.of`.

- **Response mapping.** Content is emitted with `role("model")`. Fanar's finish reason maps onto
  ADK's known vocabulary so ADK's telemetry and UI read it; the table is
  `ResponseMapper.finishReason`, pinned by `ResponseMapperTest.finishReasonsMapOntoAdksVocabulary`.
  One rule applies in both modes: when there is content, the response carries content and finish reason; when there is
  nothing to show, or the stream reported an `ErrorChunk`, it carries `errorCode` and
  `errorMessage`. Every response carries content or `errorCode`, never `errorMessage` alone,
  because of the zero-events failure above, and `errorCode` is never `STOP`: a terminal chunk
  after an error frame does not relabel the error. ADK's own Gemini model applies the first rule when not
  streaming and marks any non-stop finish as an error when streaming; the adapter does not copy
  that asymmetry. `usageMetadata` is filled from `CompletionUsage`; `modelVersion` from the model
  the server reports, not the one requested, which is how the `Islamic-RAG` alias shows up as
  `Fanar-Sadiq`.

- **Sadiq references become `groundingMetadata`; server-side tool calls are not emitted.**
  `groundingMetadata` is copied onto the event and is ADK's native home for citations, so each
  `Reference` becomes a grounding chunk carrying the source and the quoted text. The `tool_calls`
  Fanar returns are retrievals the server already performed (wire ledger, 2026-04-25); no tool was
  declared on the request under either policy, so a `functionCall` part would have ADK look for a
  tool it does not have, skip it, and re-invoke the model because the event is not final. They are
  therefore not emitted at all. The inbound half of tool calling arrives together with the outbound
  half, under a forwarding policy constant, when a model is shown to honour declared tools.

- **Streaming.** Every `TokenChunk` with text becomes a `partial(true)` response; the adapter
  accumulates the text and, when the publisher completes, emits one non-partial response carrying
  the whole turn, the finish reason, and the usage from the `DoneChunk` when one arrived.
  Completion is the trigger, not the `DoneChunk`, because core signals them separately and a
  truncated stream completes without one; `ErrorChunk`s arrive as items, not as errors. Progress
  and server-side tool chunks are not emitted. ADK asks for streaming only under
  `StreamingMode.SSE`; the default `Runner` configuration does not.

- **No scheduler hop; `Flowable.defer` around every call.** Core's `stream()` performs the HTTP
  handshake on the calling thread before returning its publisher, and `send()` blocks; both are
  wrapped in `Flowable.defer` so nothing runs, and nothing throws, before ADK subscribes, and both
  then run on ADK's subscribing thread. That thread is inside ADK's `call_llm` span scope, and the
  OpenTelemetry adapter parents on the current context, so the Fanar HTTP span nests under ADK's.
  A `subscribeOn` hop would split them into two traces. ADK's own models start the HTTP call at
  assembly and block the subscriber in `Flowable.fromFuture`; the adapter is stricter, nothing
  before subscription. The `Flow.Publisher`-to-`Flowable` bridge is
  `org.reactivestreams.FlowAdapters`, which puts `reactive-streams` in the adapter's bytecode and
  therefore among its declared dependencies.

- **The agentic case is anticipated, not designed for.** Whether `Fanar-Agentic` and
  `Fanar-Sadiq-Agentic` honour user-supplied tools is unknown and untestable with our key. The
  shape above makes the answer additive: capability is keyed by model id, so a forwarding policy
  constant is the adapter-side change; the inbound half is modelled in core and routed by the
  discriminator; the outbound half is blocked in core, where `ChatRequest` carries no `tools` or
  `tool_choice` and `Message` has no tool-result variant. Those two additions follow ADR-015, land
  in both codecs with parity tests, and change `Message`'s sealed permits — a minor-version event
  before 1.0.0 and a major one after (ADR-019). A `live`-tagged test in `e2e` asserting the
  current 422 gate turns the day our key is granted into a signal, which the silent-drop probe the
  ledger declined to retain could not pin.

- **No retry, no logging, no exception wrapping in the adapter.** ADK ships no retry around
  `generateContent`; `RetryPolicy` (ADR-014, ADR-025, ADR-027) already covers it on the client.
  Core has no logging dependency and neither does the adapter; the `ObservabilityPlugin` is the
  visibility surface. `FanarException` propagates as `Flowable.error` with `code()` and
  `httpStatus()` intact, reaching ADK's `onModelErrorCallback` and plugin hooks, as ADR-025 records
  for the Spring AI adapter; ADK hands any `Exception` to those hooks unchanged.

- **Dependencies.** `fanar-core` compile; `fanar-json-jackson2` runtime; ADK, google-genai, RxJava
  and reactive-streams `provided`, pinned in the reactor's version catalogue — genai, RxJava and
  reactive-streams because their types appear in the adapter's signatures or bytecode, which strict
  `dependency:analyze` sees. No Guava: the obvious implementation reaches for its immutable
  collections, and that is a declared dependency bought for types the JDK has. ADK 1.9 is a
  released, post-1.0 line and satisfies JLBP-4; its `models` package nonetheless carries no
  documented stability guarantee, so an ADK bump runs the seam tests, not just a compile.

- **Non-goals.** `connect()` throws `UnsupportedOperationException`, as ADK's own text-only models
  do. Inline bytes are not mapped: `ImagePart` documents `data:` URIs, so the mapping is additive
  once the ledger records that Fanar accepts them. No embeddings. No ADK `BaseTool` wrappers for
  the other domain facades — a separate record if wanted. No sample in this record's PR; the
  dev-server sample that reproduces the loader failure is a follow-up.

- **Ordering, layout and docs.** ADK goes ahead of LangChain4j because its integration cost is
  measured and the others' is not, and because the ADK dev UI gives the SDK a demo surface. The
  module is a flat top-level directory; ADR-010 is corrected in place to say when nesting is paid.
  The implementing PR updates every document that names the module set, counts published
  artifacts, or names LangChain4j as the next adapter, and adds ADK-specific bullets to
  `COMPATIBILITY.md` §3 rather than pointing ADK readers at a Spring AI list. ADR-021's revisit
  trigger names this record.

## Alternatives considered

- **Leave the adapter in user code.** The Context section is the measurement: six failure modes,
  five of them silent, none discoverable from `BaseLlm`'s signature.
- **Layer on `fanar-spring-boot-4-starter`, as ADR-021 does.** *Rejected*: every ADK user would
  become a Spring Boot user. ADR-021 itself lists direct-on-core as the option for a non-Boot
  audience.
- **Codec at test scope; the consumer adds one.** *Rejected*: it ships the loader failure as the
  default experience, and ADK already puts Jackson 2 on the classpath, so the runtime-scope codec
  costs the consumer nothing.
- **The Jackson 3 codec instead.** *Rejected*: only the dev server, a Spring Boot 4 application,
  brings Jackson 3; ADK core brings Jackson 2 unconditionally.
- **Degrade silently on tools, as ADR-021 does.** *Rejected* for ADK: the ledger's finding is that
  the caller gets no signal; in Spring AI that costs one advisor, in ADK it costs the runner's
  control flow, including transfers the user never asked for.
- **A boolean instead of a policy.** *Rejected*: a forwarding mode would be a signature change.
- **A `FanarException` subtype for the refusal.** *Rejected*: impossible from outside core (sealed
  hierarchy, named module), and wrong in kind — nothing went on the wire.
- **A generic `Map<String, Object>` for Fanar extras.** *Rejected* in ADR-024, for reasons that do
  not change here.
- **Surface server-side tool calls as `customMetadata`.** *Rejected*: ADK 1.9 drops it at the
  event boundary.
- **Map server-side tool calls to ADK function calls.** *Rejected*: ADK would execute them, and an
  already-executed retrieval is not a pending call.
- **`subscribeOn(Schedulers.io())` to keep the HTTP call off ADK's thread.** *Rejected*: it splits
  the trace, and ADK's own models block the subscribing thread.
- **Wait for LangChain4j first**, per the published roadmap. *Rejected*, with the ordering
  rationale above.

## Consequences

### Positive
- One dependency plus ADK produces a working agent: the codec is on the classpath, the client can
  be built lazily, and the dev UI, sessions, callbacks and plugins compose on top.
- Every configuration decision stays on `FanarClient.builder()`. The adapter adds no transport,
  retry, auth or observability surface and requires nothing new from core — the split ADR-002 and
  ADR-003 describe, with RxJava confined to the adapter as ADR-003 requires.
- Observability needs no new SPI. ADK's `call_llm` span reads usage, finish reason and model
  version off our responses, and the Fanar HTTP span nests under it when the OpenTelemetry adapter
  is on the client.
- A refusal naming the tools or the schema is recoverable by the caller; a silent no-op is not.
- Sadiq citations survive into ADK sessions as grounding metadata.

### Negative / Trade-offs
- Tool-using, schema-using and freely-transferring ADK setups are refused rather than degraded,
  which will arrive as support questions; the two multi-agent recipes and `IGNORE` are the
  answers.
- Coupled to the ADK 1.9 line, whose streaming protocol is enforced only by `BaseLlmFlow`'s
  behaviour. An ADK bump is a seam-test run.
- ADK's dependency tree is large and reaches the adapter's test classpath through `provided`
  scope; CI's newer-JDK leg has to load it.
- The `Runner`-level tests depend on ADK internals — `InMemorySessionService`, transfer injection —
  that may move.
- `fanar-adk` lands in 0.7.0, before the ADR-019 freeze, and is frozen with everything else at
  1.0.0; the repo has no experimental tier, so the surface stays minimal. The ADR-029 rename, if it
  happens, covers `qa.fanar.adk` too.

### Neutral
- The BOM entry lands in the same change as the module; `check-build` asserts set equality in both
  directions.
- ADK hard-codes the `gen_ai.system` attribute to its own value for every model; Fanar calls are
  told apart by `gen_ai.request.model`.
- The ledger's open question stays open and is the one finding that would reshape this record: the
  `-Agentic` pair answers 422 for our key. Until it resolves, ADK agents on Fanar are text-only and
  either single agents, workflow steps or leaf specialists.

## Proved by

Seam-crossing, in `qa.fanar.adk`, through `FanarClient.builder()` and `ScriptedHttpServer`:

- `FanarLlmIntegrationTest` — the outbound body (`0.7` exactly, no `tools` key, roles, one system
  message); the non-streaming mapping (role `model`, usage, finish reason, server-reported model
  version); streaming partials followed by exactly one final response; a truncated stream still
  finalising; an `ErrorChunk` becoming `errorCode`; `references` becoming grounding metadata. Hit
  counts asserted throughout.
- `FanarLlmErrorIntegrationTest` — a 429 whose `Retry-After` exceeds the budget surfaces as
  `FanarRateLimitException` through `Flowable.error` with `code()`, `httpStatus()` and
  `retryAfter()` intact and reaches `onModelErrorCallback`; a 503 followed by a 200 costs two
  hits, proving the client's policy applies and the adapter adds none; a client supplier that
  fails surfaces its own message through the callback and the caller with zero hits.
- `FanarLeafIntegrationTest` — a stand-in tool-capable root transfers to a Fanar leaf that
  disallows transfer to parent and peers: no tool in the leaf's request, one hit, and the next
  user turn goes back to the root with Fanar uncalled.
- `FanarLlmTracingIntegrationTest` — with one tracer provider on both sides, the Fanar span's
  parent is ADK's `call_llm` span, non-streaming and streaming.
- `FanarLlmPolicyIntegrationTest` — `REJECT` with a declared tool, with an `AgentTransfer`-injected
  tool through a real `Runner` and sub-agent, and with an output schema: zero hits and an exception
  naming the item; `IGNORE`: one hit and no `tools` key on the wire; a two-step workflow costs two
  hits with no tool, the second carrying ADK's narration of step one as a second user message.
- `FanarRunnerIntegrationTest` — a streaming turn through `Runner` and `InMemorySessionService`
  costs exactly one hit, persists one non-partial event with role `model`, and the next turn
  re-sends it as history.
- `FanarLlmRegistryIntegrationTest` — no `fanar/` pattern exists until registration; a registered
  `fanar/…` name resolves lazily on the first step and reuses one instance.
- Live, in `e2e`: `LiveAgenticGateTest` — `Fanar-Agentic` answers 422 "Model not authorized" today,
  before any tools question can be asked (core carries no `tools` field); pinned by a dated ledger
  row, it fails loudly the day the gate lifts.
- Units: the config conversion, the finish-reason table, the stream aggregator and the part
  mapping.

## References

- ADR-002, ADR-003 — narrow core scope; adapters are the designated place for framework types,
  RxJava included.
- ADR-004, ADR-005 — sync-primary and `Flow.Publisher` streaming; why the RxJava bridge lives here.
- ADR-006 — the sealed exception hierarchy; why the refusal is not a `FanarException`.
- ADR-008, ADR-016 — codec discovery and the missing-codec message.
- ADR-010, ADR-011 — module layout and package conventions; ADR-010 corrected in place for the flat
  layout.
- ADR-014, ADR-025, ADR-027 — retry policy and the unwrapped-exception precedent; why the adapter
  adds no retry.
- ADR-015 — DTO conventions; the form the core additions take if the agentic question resolves in
  favour of user tools.
- ADR-019 — pre-1.0 policy; freeze timing and the cost of sealed-permits changes.
- ADR-020, ADR-021, ADR-024 — the Spring adapters this record departs from, and where.
- [`WIRE_OBSERVATIONS.md`](../WIRE_OBSERVATIONS.md#chat-completions--post-v1chatcompletions) —
  `tools` accepted and ignored (2026-09-15), the gated `-Agentic` pair, `Islamic-RAG` as an alias.
- [`COMPATIBILITY.md`](../COMPATIBILITY.md) §3 — the deferred-with-rationale list this record
  extends.
- [`PROJECT_STATE.md`](../PROJECT_STATE.md) — the roadmap this record reorders.
- [`JAVA_LIBRARY_BEST_PRACTICES.md`](../JAVA_LIBRARY_BEST_PRACTICES.md) — JLBP-4, the third-party
  dependency rule this record was checked against.
- [Google ADK Java](https://github.com/google/adk-java) — `BaseLlm`, `BaseLlmFlow`, `Runner`,
  `AgentTransfer`, `LlmRegistry`, `Contents` and `CompiledAgentLoader` are the types named
  throughout; behaviour verified against the 1.9.0 sources.
