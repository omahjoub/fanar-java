# Changelog

All notable changes to the Fanar Java SDK are recorded here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) with the
[pre-1.0 caveats from ADR-019](docs/adr/019-pre-10-stability-policy.md) — minor versions
may break public API until 1.0.0 ships.

## [Unreleased]

## [0.1.0] - 2026-04-28

Initial public release. Pre-1.0; not yet on Maven Central — install via `./mvnw install`
from a clone, or download the artifacts attached to this release.

### Added

- **`fanar-core`** — typed `FanarClient` with eight domain facades
  (`chat` / `models` / `tokens` / `moderations` / `translations` / `poems` / `images` / `audio`),
  sealed `FanarException` hierarchy, SSE streaming via `Flow.Publisher<StreamEvent>`,
  retry policy with jitter, interceptor chain, observability SPI. Sync + async + streaming
  on every domain. 100 % JaCoCo coverage. Zero runtime dependencies.
- **JSON codecs** — `fanar-json-jackson2` (Spring Boot 3 / Jackson 2) and
  `fanar-json-jackson3` (Spring Boot 4 / Jackson 3). `ServiceLoader` discovery, GraalVM
  reachability metadata.
- **Observability adapters** — `fanar-obs-slf4j`, `fanar-obs-otel`, `fanar-obs-micrometer`.
  Wire any combination via `ObservabilityPlugin.compose(...)`.
- **`fanar-interceptor-logging`** — OkHttp-style wire-logging interceptor with
  `NONE` / `BASIC` / `HEADERS` / `BODY` levels, SLF4J sink, header redaction,
  body byte cap, streaming-aware.
- **`fanar-spring-boot-4-starter`** — `@AutoConfiguration` registering `FanarClient`
  from typed `fanar.*` properties; auto-wired `Interceptor` / `ObservabilityPlugin`
  beans; `FanarHealthIndicator` activated when `spring-boot-health` is on the classpath.
- **`fanar-spring-ai-starter`** — Spring AI 2.0 (pinned to `2.0.0-M4`) `ChatModel` +
  `StreamingChatModel` + `ImageModel` + `TextToSpeechModel` + `TranscriptionModel`
  adapters. Memory + RAG advisors compose via Spring AI's `ChatClient`.
- **Sample apps** — `fanar-spring-boot-4-sample` and `fanar-spring-ai-sample`,
  both runnable fat jars (`java -jar … && export FANAR_API_KEY=…`).
- **GraalVM native-image** — reachability metadata for the 38 records the JSON codec
  touches; `e2e-graalvm` module with self-test + live-walk modes; PR-time native smoke
  workflow; bootstrap workflow for re-tracing metadata.
- **Live e2e suite** (`fanar-java-e2e`) — parameterised over both codecs across every
  domain, gated on `FANAR_API_KEY`; offline by default, opt-in for live runs.
- **CI** — Java 21 + 25 build matrix, JaCoCo 100 % gate, `dependency:analyze` strict
  mode, doclint at javac time, JaCoCo report uploaded as artifact on failure for
  flake diagnosis, doc-link verification.
- **`fanar-java-bom`** — version alignment for multi-module consumers.

### Known limitations

- **Spring AI `ModerationModel`** — not implemented. Fanar's moderation returns
  continuous safety + cultural-awareness scores; Spring AI's surface expects 16
  category booleans. Use `FanarClient.moderations()` directly. ([rationale](docs/adr/021-spring-ai-2-adapter.md))
- **Spring AI `EmbeddingModel`** — not implemented. Fanar exposes no embeddings
  endpoint. RAG users bring an external embedder
  (`spring-ai-openai`, `spring-ai-transformers`, etc.).
- **User-supplied tool calling** — Fanar rejects user `tools` / `tool_choice`
  server-side. Spring AI's tool-callback advisors degrade silently in our adapter.
- **Native chat structured output** — Fanar exposes no `response_format` field.
  Spring AI's prompt-engineering converters (`BeanOutputConverter`) still work
  end-to-end since they shape the prompt text.
- **Fanar `stop` parameter** — silently dropped server-side; documented in tests.

[Unreleased]: https://github.com/omahjoub/fanar-java/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/omahjoub/fanar-java/releases/tag/v0.1.0
