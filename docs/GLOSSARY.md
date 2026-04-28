# Glossary

A reader-friendly reference for terms that appear across the SDK docs. Two audiences are served: JVM developers
new to Fanar, and Arabic/Islamic-AI practitioners new to Java library conventions. Entries are short — follow
the links for depth.

---

## Fanar — the platform

- **Fanar** — Qatar's Arabic-centric multimodal AI platform. Hosts all the models below. Base URL `https://api.fanar.qa`.
- **Fanar API** — the HTTP API this SDK targets. OpenAPI 3.1.0 spec committed at [`api-spec/openapi.json`](../api-spec/openapi.json): 12 endpoints, 14 models.
- **OpenAI-compatible** — Fanar's chat endpoint accepts OpenAI-style request shapes. This SDK still exists because Fanar offers capabilities OpenAI does not (see [Compatibility matrix](COMPATIBILITY.md)).

## Fanar models

Exact model IDs as accepted by the API.

### Chat and reasoning

- **`Fanar`** — default chat router; picks the right backend for the query.
- **`Fanar-S-1-7B`** — "Star" chat model, 7 B parameters.
- **`Fanar-C-1-8.7B`** — "Commander" chat model with thinking support, version 1.
- **`Fanar-C-2-27B`** — "Commander" chat model with thinking support, version 2. Required for `enable_thinking=true` (with extra authorization).
- **`Fanar-Sadiq`** — Islamic RAG model. Returns authenticated source references.

### Vision

- **`Fanar-Oryx-IVU-2`** — vision-language model. Arabic-calligraphy-aware.

### Image generation

- **`Fanar-Oryx-IG-2`** — image generation model. Returns base64 PNG.

### Speech

- **`Fanar-Aura-TTS-2`** — general text-to-speech.
- **`Fanar-Sadiq-TTS-1`** — Quranic text-to-speech with validated reciters.
- **`Fanar-Aura-STT-1`** — speech-to-text for short clips (≤ 20–30 s).
- **`Fanar-Aura-STT-LF-1`** — speech-to-text for long-form audio with speaker-diarized segments.

### Utility

- **`Fanar-Guard-2`** — content moderation. Returns both a safety score and a cultural-awareness score.
- **`Fanar-Shaheen-MT-1`** — machine translation (EN ↔ AR) with HTML/whitespace-preserving preprocessing modes.
- **`Fanar-Diwan`** — Arabic poetry generation.

## Fanar-exclusive concepts

- **Islamic RAG** — retrieval-augmented generation restricted to authenticated Islamic sources (`quran`, `tafsir`, `sunnah`, `dorar`, `islamweb*`, `islam_qa`, `islamonline`, `shamela`). Implemented by `Fanar-Sadiq`.
- **References (`references[]`)** — authenticated source citations returned in chat responses from `Fanar-Sadiq`. Each entry: `{number, source, content}`.
- **Thinking mode — two coexisting protocols**:
  1. The `enable_thinking` request flag (model-gated, extra-authz).
  2. First-class conversation roles (`thinking`, `thinking_user`) for persisting reasoning traces across turns.
- **Bilingual progress events** — mid-stream `ProgressChunk.progress.message = {en, ar}` signalling intermediate processing steps in both languages.
- **Cultural-awareness score** — a second moderation signal alongside the standard safety score, returned by `Fanar-Guard-2`.
- **Tajweed** — the rules governing correct Quranic recitation. `Fanar-Sadiq-TTS-1` honors these rules.
- **Quranic reciter** — one of `abdul-basit`, `maher-al-muaiqly`, `mahmoud-al-husary`. Selectable on `Fanar-Sadiq-TTS-1`.
- **Voice cloning** — creating a named personalized voice from a WAV sample plus transcript. Endpoints under `/v1/audio/voices`.
- **`restrict_to_islamic`** — a `Fanar-Sadiq` request flag that server-side rejects non-Islamic prompts.
- **Source scoping** (`preferred_sources`, `exclude_sources`, `filter_sources`, `book_names`) — `Fanar-Sadiq` controls that narrow retrieval to specific corpora.

## Java / JVM terms

- **ADR** — Architecture Decision Record. A short document capturing one decision with its context, alternatives, and consequences. See [docs/adr/INDEX.md](adr/INDEX.md).
- **BOM** — Bill of Materials. A Maven `packaging=pom` artifact that pins aligned versions of related modules. Our BOM is `fanar-java-bom`.
- **JPMS** — Java Platform Module System. Each module has a `module-info.java` declaring `requires` / `exports` / eventually `provides` and `uses`.
- **LTS** — Long-Term Support release of Java (17, 21, 25, 29, …). Our core minimum is Java 21.
- **Pattern-matching `switch`** — Java 21+ feature that enables exhaustive `switch` over a sealed hierarchy. Used to consume `StreamEvent`.
- **Record** — Java immutable data type with auto-generated canonical constructor, `equals`, `hashCode`, `toString`. All our DTOs are records.
- **Sealed interface** — a restricted interface whose implementations are a fixed, compiler-known set. Enables exhaustive pattern matching.
- **SPI** — Service Provider Interface. An interface with one or more pluggable implementations, discovered at runtime (typically via `ServiceLoader`). Our SPIs: `FanarJsonCodec`, `Interceptor`, `ObservabilityPlugin`.
- **`ServiceLoader`** — JDK mechanism for discovering SPI implementations at runtime from `META-INF/services/` or `module-info.java` `provides` clauses.
- **Virtual threads** — Java 21 lightweight threads. Blocking on I/O does not tie up a carrier thread. Underpins our sync-primary API model.
- **`provided` scope** — a Maven dependency that must be on the runtime classpath but is not transitively added for consumers. Our Jackson adapters declare Jackson as `provided` so the user's Spring Boot supplies the concrete runtime.
- **GraalVM native-image** — ahead-of-time compilation from Java bytecode to a native executable. Requires reachability metadata for reflection-heavy code. Our artifacts ship that metadata (ADR-009).
- **Reachability metadata** — JSON files under `META-INF/native-image/` telling GraalVM which classes and members reflection will touch at runtime.

## SDK-specific terms

- **Core** — the `fanar-core` module and its public API: typed, pluggable, observable transport for the Fanar API. Zero runtime dependencies (ADR-002).
- **Downstream module** — a module that sits on top of the core, typically adapting it to a framework or adding higher-level capabilities (memory, templating, vectors, evaluation). Out of core scope.
- **Domain facade** — a sub-client exposed from `FanarClient`: `client.chat()`, `client.audio()`, `client.images()`, `client.translations()`, `client.poems()`, `client.moderations()`, `client.tokens()`, `client.models()`. See ADR-016.
- **Interceptor chain** — Chain-of-Responsibility for cross-cutting concerns (auth, retry, rate-limit, logging, caching, custom). See ADR-012.
- **Lighthouse** — shorthand for [COMPATIBILITY.md](COMPATIBILITY.md), the authoritative "what's in / out / framework-layer" matrix.
- **Observability plugin** — our unified metrics + tracing SPI, one per `FanarClient`. See ADR-013. `ObservabilityPlugin.compose(...)` fans out a single slot to multiple plugins.
- **Seam** — an extension point on the core where a user or downstream module can supply an alternative (HTTP client, JSON codec, observability backend, interceptor, retry policy). Every seam is behind a typed interface.
- **SSE** — Server-Sent Events. HTTP content type `text/event-stream`, used by Fanar's streaming chat endpoint. Parsed internally; dispatched as typed `StreamEvent` on `Flow.Publisher`.
- **`StreamEvent`** — the sealed interface over SSE chunk types: `TokenChunk`, `ToolCallChunk`, `ToolResultChunk`, `ProgressChunk`, `DoneChunk`, `ErrorChunk`. Consumed via pattern-matching switch.
- **Wire-logging interceptor** — `qa.fanar.interceptor.logging.WireLoggingInterceptor`, OkHttp-style level ladder (`NONE` / `BASIC` / `HEADERS` / `BODY`). Logs to SLF4J under `fanar.wire`. Auto-wired by the SB4 starter when `fanar.wire-logging.level` ≠ `NONE`.

## Framework-adapter terms (Spring Boot 4 + Spring AI)

Some words are overloaded — the Spring AI **`ChatModel`** type is unrelated to Fanar's `ChatModel` model-id record. The list below disambiguates.

- **`FanarHealthIndicator`** — Spring Boot Actuator health contributor in the SB4 starter. Calls `client.models().list()` and reports UP / DOWN with diagnostics. Disable via `management.health.fanar.enabled=false`.
- **Spring AI `ChatModel`** — `org.springframework.ai.chat.model.ChatModel`. Spring AI's provider SPI; we implement it in `FanarChatModel`. *Distinct* from `qa.fanar.core.chat.ChatModel`, which is a typed wrapper around the Fanar wire model id (e.g. `"Fanar"`, `"Fanar-S-1-7B"`).
- **Spring AI `ChatClient`** — `org.springframework.ai.chat.client.ChatClient`. Spring AI's high-level fluent surface (`chat.prompt().user(...).call()`); built on top of a `ChatModel`. Memory advisors, RAG advisors, prompt templates, structured-output converters all attach here.
- **Advisor** — Spring AI's interceptor for the `ChatClient` chain. Runs `before()` to mutate the outbound prompt and `after()` to mutate the response. Memory and RAG are advisors.
- **`ChatMemory`** — Spring AI's chat-history SPI. We use `MessageWindowChatMemory` (sliding window, in-memory) in the sample; production apps swap for the JDBC / Redis variants Spring AI ships.
- **`MessageChatMemoryAdvisor`** — Spring AI's memory advisor. Loads prior messages into the prompt by `conversationId`, persists the response on the way out.

## Build / tooling terms

- **`-parameters`** — javac flag that emits method-parameter names into bytecode. Required for Spring MVC's `@PathVariable String foo` / `@RequestParam String bar` to bind by reflection without an explicit name argument. Enabled globally in our parent POM.
- **GraalVM tracing agent** — `-agentlib:native-image-agent` records every reflective lookup, classpath resource read, JNI call, dynamic proxy, and serialization seen during a JVM run. Output JSON files become reachability metadata for AOT compilation. Used to bootstrap our `META-INF/native-image/` payload.
- **Reachability Metadata Repository (GRMR)** — community-maintained metadata index Oracle ships with GraalVM. We disable it in our build (`metadataRepository.enabled=false`) because we control our own metadata; mixing the two leads to schema-mismatch failures across GraalVM versions.
