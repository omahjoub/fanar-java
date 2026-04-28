# Project state

> **Snapshot — 2026-04-28.** Updated on every milestone. If this looks wrong or stale, that is
> the signal — update it in the same PR as whatever moved.

## Phase

**Implementation phase — framework adapters shipping.** The core SDK and all Fanar domains
are feature-complete with 100 % JaCoCo coverage and live e2e tests against the real API. Spring
Boot 4 + Spring AI 2.0 starters are merged with sample apps. Pre-1.0; no Maven Central
artifacts yet.

## Shipped

| Layer | Module(s) | Highlights |
|---|---|---|
| Core SDK | `fanar-core` | `FanarClient` + 8 typed domain facades (chat / models / tokens / moderations / translations / poems / images / audio). Sealed `FanarException` hierarchy. SSE streaming via `Flow.Publisher<StreamEvent>`. Sync + async + streaming. 100 % JaCoCo. |
| JSON codecs | `fanar-json-jackson2`, `fanar-json-jackson3` | Snake-case wire format, NON_NULL inclusion, six flattening deserializers, generic wire-value module, `ServiceLoader` discovery, GraalVM reachability metadata. |
| Observability | `fanar-obs-slf4j`, `fanar-obs-otel`, `fanar-obs-micrometer` | One adapter per backend; opt-in (no `ServiceLoader`). `ObservabilityPlugin.compose(...)` factory wires multiple adapters into a single slot. |
| Interceptors | `fanar-interceptor-logging` | OkHttp-style level ladder (`NONE` / `BASIC` / `HEADERS` / `BODY`), SLF4J sink at `fanar.wire`, redaction, body cap, streaming-aware. |
| Live tests | `fanar-java-e2e` | Parameterized over both codecs. 19 chat-completion shapes × 2 codecs + every other domain (audio TTS+STT, voices, images, translations, moderations, tokens, models, poems). Gated on `FANAR_API_KEY`. |
| GraalVM | `fanar-java-e2e-graalvm` | Fat-jar + `native-image` profile. Self-test mode (offline: 9 decode + 9 encode probes + obs plugins + interceptor) and live mode covering every domain. CI: PR-time native-smoke + workflow-dispatch metadata bootstrap. |
| Spring Boot 4 | `fanar-spring-boot-4-starter`, `fanar-spring-boot-4-sample` | `@AutoConfiguration` + typed `FanarProperties` record + auto-wired `Interceptor` / `ObservabilityPlugin` beans + `FanarHealthIndicator` (Actuator). Sample app exercises the wiring end-to-end. |
| Spring AI 2.0 | `fanar-spring-ai-starter`, `fanar-spring-ai-sample` | `ChatModel` (with streaming) + `ImageModel` + `TextToSpeechModel` + `TranscriptionModel` adapters, depending on the SB4 starter. Sample uses `ChatClient` with `MessageChatMemoryAdvisor` for multi-turn. Pinned to Spring AI `2.0.0-M4`. |
| Build / CI | parent POM, `.github/workflows/ci.yml` | Java 21 + 25 matrix, JaCoCo 100 % gate on every shipping module, `dependency:analyze` strict mode, doclint at javac time, JaCoCo report uploaded as artifact on failure for flake diagnosis, `-parameters` flag enabled globally. |

## Planned

- **Maven Central publication** — Sonatype account, GPG signing, release workflow, version-bump policy. Gates v0.1.0.
- **Spring Boot 3 starter** — `fanar-spring-boot-3-starter` with the Jackson 2 codec; mechanical port of the SB4 starter.
- **LangChain4j adapter** — `fanar-langchain4j` exposing the equivalent of Spring AI's adapters against LangChain4j's `ChatLanguageModel`.
- **Quarkus extension** — CDI beans, build-time wiring, native-image friendliness.
- **Nightly live e2e on CI** — scheduled job runs `fanar-java-e2e` with `FANAR_API_KEY` injected as a secret; PR builds stay offline.

## Deferred (won't fit cleanly)

- **Spring AI `ModerationModel`** — Fanar's `/v1/moderations` returns continuous `safety` + `culturalAwareness` scores; Spring AI's surface expects 16 category booleans. A best-effort mapping would always report `Categories.isHate()=false`, which is misleading. Surfaced via `FanarClient.moderations()` directly instead.
- **Spring AI `EmbeddingModel`** — Fanar exposes no `/v1/embeddings` endpoint at all. Users wanting RAG bring their own embedder (`spring-ai-openai`, `spring-ai-transformers`, etc.).
- **Native `response_format` / structured output on chat** — not in the Fanar wire spec. Spring AI's prompt-engineering converters (`BeanOutputConverter`) still work because they shape the prompt text, not the request flag.
- **User-supplied tool calling** — Fanar's `/v1/chat/completions` rejects user `tools` / `tool_choice`. The `tool_calls` events in streams are server-internal Sadiq retriever telemetry. Spring AI tool callbacks degrade silently in our adapter.
- **Fanar `stop` parameter** — silently dropped server-side; documented in tests.

## Cadence for updates

Update this file when:

- A milestone ships (new module, new framework adapter, version-tag, public release).
- An ADR gets superseded.
- A `Planned` item moves to `Shipped`, or a `Deferred` item gains traction.

Commit the update in the same PR as the change that motivated it — never separately.
