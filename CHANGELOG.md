# Changelog

All notable changes to the Fanar Java SDK are recorded here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) with the
[pre-1.0 caveats from ADR-019](docs/adr/019-pre-10-stability-policy.md) — minor versions
may break public API until 1.0.0 ships.

## [Unreleased]

### Added

- **`fanar-core`** — `FanarQuotaExceededException.retryAfter()`: the server's `Retry-After`
  countdown now survives on both HTTP 429 subtypes, not only `FanarRateLimitException`
  ([ADR-025](docs/adr/025-retry-after-handling.md)).
- **`fanar-spring-boot-4-starter`** — `fanar.retry.max-delay` (default `30s`) and a
  `@ConditionalOnMissingBean RetryPolicy` bean: declare your own `RetryPolicy` bean for a custom
  `retryable` predicate, jitter or multiplier without replacing the `FanarClient` bean
  ([ADR-020](docs/adr/020-spring-boot-4-starter.md), amended).
- **`fanar-java-e2e`** — `LiveRateLimitHeadersTest`, a transport-level live pin of the
  rate-limit response-header contract (single codec; headers are codec-independent).

### Changed

- **`fanar-core`** — **retries now fire on HTTP 429 / 500 / 503 / 504** (see *Fixed*). Callers
  see the sleeps and re-requests ADR-014 always described; the default policy's worst case is
  two honoured `Retry-After` hints of up to 30 s each. `RetryPolicy.disabled()` opts out.
- **`fanar-core`** — `Retry-After` semantics ([ADR-025](docs/adr/025-retry-after-handling.md)):
  a hint is honoured up to `RetryPolicy.maxDelay()`; a larger hint ends retrying immediately and
  the exception surfaces with `retryAfter()` populated so callers can schedule around the wait.
  Non-positive, past-date or unparseable hints count as absent (computed backoff applies); a
  future HTTP-date becomes the remaining wait. The ceiling applies regardless of the `retryable`
  predicate — documented on `RetryPolicy`. `fanar.retry_count` is now recorded on every call
  (`0` included) and `http.status_code` per attempt.
- **`fanar-core`** — `RetryPolicy` rejects a `maxDelay` that is not representable in
  milliseconds at construction (previously an `ArithmeticException` at retry time).
- **`fanar-spring-boot-4-starter`** — `fanar.retry.initial-backoff` now defaults to `500ms`
  (was `100ms`), matching `RetryPolicy.defaults()` as the properties javadoc always claimed.
- **`api-spec`** — absorbed the 2026-08-27 Fanar spec refresh. Structurally a no-op (all 12
  operations, 97 schemas, and the per-model rate-limit table are unchanged; `info.version`
  still 1.0.0); the refresh documents the previously-unspecified rate-limit response headers:
  `x-ratelimit-limit` / `x-ratelimit-remaining` / `x-ratelimit-reset`, `ratelimit-policy`
  (`limit;w=seconds` — the only way to distinguish a per-minute from a per-day window), and
  `retry-after` (429-only, in seconds). Verified live 2026-08-27: chat 2xx responses carry all
  four quota headers (`50;w=60` on chat models); `GET /v1/models` and 401 responses carry none.
  2026-08-28: an exhausted per-day TTS window (`20;w=86400`) answered 429 `rate_limit_reached`
  with `retry-after` equal to `x-ratelimit-reset` (~8 h) — surfaced immediately by the ADR-025
  ceiling. Typed exposure of the window headers is deferred (see PROJECT_STATE); read them via
  the `Interceptor` SPI.

### Fixed

- **`fanar-core`** — HTTP-status retry never fired. Every domain facade mapped 4xx/5xx to the
  typed hierarchy *after* the interceptor chain returned, so the built-in `RetryInterceptor`
  only ever retried transport failures — ADR-014's retryable set was dead end-to-end in 0.1.0
  and 0.2.0. The mapping now happens inside the chain at the retry boundary
  ([ADR-012](docs/adr/012-interceptor-spi.md) and
  [ADR-006](docs/adr/006-unchecked-exception-hierarchy.md), amended); user interceptors still
  see raw error responses. Covered end-to-end by `FanarClientRetryIntegrationTest` — the public
  builder against a local `HttpServer` scripting 5xx / 429 / `Retry-After` sequences — plus a
  facade-level test driving 503 → 200 through the real chain.

## [0.2.0] - 2026-08-06

Full parity with the 2026-08 Fanar spec: madhab-aware `Fanar-Sadiq-2`, custom personas,
streamed + emotional TTS, a rich voice catalogue, culturally-aligned image prompt revision,
and typed envelope-code error routing. Pre-1.0 ([ADR-019](docs/adr/019-pre-10-stability-policy.md)):
this release contains three breaking changes, marked below. Not yet on Maven Central — install
via `./mvnw install` from a clone, or download the artifacts attached to this release.

### Added

- **`fanar-core`** — streaming TTS: `AudioClient.speechStream(request)` returns
  `Flow.Publisher<byte[]>` and delivers the audio chunked as the server generates it
  (the wire `stream:true` mode, mp3 + wav). Single-subscriber, back-pressured, cancel closes
  the connection — the same contract as chat streaming.
  ([ADR-023](docs/adr/023-streaming-tts-via-flow-publisher.md))
- **`fanar-spring-ai-starter`** — `FanarTextToSpeechModel.stream(...)` now streams for real:
  one `TextToSpeechResponse` per audio chunk via `speechStream`, replacing the previous
  single-element-Flux emulation.
- **`fanar-core`** — audio: `Voice.ABDULRAHMAN` and `Voice.RADWA` (the two emotion-capable
  built-ins), `TextToSpeechRequest.withEmotion` (emotional synthesis — `Fanar-Aura-TTS-2` +
  emotion-capable voices only, otherwise HTTP 422) plus a fluent
  `TextToSpeechRequest.builder()`, and the rich voice catalogue types `AvailableVoice` /
  `VoiceType` returned by `listVoices()`.
- **`fanar-core`** — `ChatModel.FANAR_SADIQ_2` (madhab-aware Islamic RAG, extra authorization
  required) plus two new `ChatRequest` fields: `persona` (custom assistant voice/identity,
  `Fanar-Sadiq` only, ≤ 2000 chars) and `madhab` (list of the new open value class `Madhab`:
  `ALL` / `HANAFI` / `MALIKI` / `SHAFII` / `HANBALI`, honoured by `Fanar-Sadiq-2`).
  Both codecs serialize `Madhab` via their wire-value modules.
- **`fanar-core`** — images: `ImageGenerationRequest.revise` (server default **true** — automatic
  prompt revision for style/quality/cultural alignment; pass `false` to keep the prompt verbatim).
- **`fanar-spring-ai-starter`** — `FanarImageGenerationMetadata(revised, revisedPrompt)` attached
  to every `ImageGeneration`, and the previously-dropped `created` timestamp now fills
  `ImageResponseMetadata`.
- **`fanar-spring-ai-starter`** — vendor options
  ([ADR-024](docs/adr/024-spring-ai-vendor-options.md)): `FanarChatOptions` (persona, madhab,
  thinking mode, Islamic-RAG scoping, logit bias, and the vLLM sampling knobs — all previously
  unreachable through portable `ChatOptions`), `FanarTextToSpeechOptions` (`withEmotion`,
  `quranReciter`), and `FanarImageOptions` (`revise`). `FanarChatOptions.Builder` extends
  Spring AI's `DefaultChatOptionsBuilder`, so the extras survive the `ChatClient`
  `mutate()`/`combineWith()` pipeline.
- **`fanar-core`** — `ErrorCode.CLIENT_CLOSED_REQUEST` and `FanarClientClosedRequestException`
  (HTTP 499, `client_closed_request`), which the 2026-08 Fanar spec declares on every endpoint.
  Correctly classified as non-retryable; previously a 499 fell into the generic 5xx fallback and
  was retried.

### Changed

- **`fanar-core`** — `ExceptionMapper` now parses the Fanar error envelope and routes by the typed
  `error.code` first, falling back to HTTP status for non-envelope bodies. `FanarQuotaExceededException`
  is now reachable (previously every HTTP 429 surfaced as `FanarRateLimitException`), and a
  non-filter 400 no longer surfaces as `FanarContentFilterException`. Exception messages now carry
  the envelope's `message` instead of the raw JSON body when available.
  ([ADR-006 amendment](docs/adr/006-unchecked-exception-hierarchy.md))
- **Breaking** — `ImageGenerationItem` is now
  `(String b64Json, boolean revised, String revisedPrompt)` (was single-component), matching the
  spec's now-required response fields.
- **Breaking** — `VoiceResponse.voices()` is now `List<AvailableVoice>` (was `List<String>`),
  matching the 2026-08 spec's rich voice objects; the listing now always includes the built-in
  public voices, not only personalized ones. Use `AvailableVoice.name()` where the raw string
  was used before.
- **Breaking** — `FanarClientException` gained a ninth permitted subtype
  (`FanarClientClosedRequestException`). Exhaustive `switch` expressions over the leaves of
  `FanarClientException` no longer compile until the new case is added; switches over the four
  top-level `FanarException` branches are unaffected. Allowed pre-1.0 per
  [ADR-019](docs/adr/019-pre-10-stability-policy.md).
- **Spec** — `api-spec/openapi.json` refreshed to the 2026-08-05 Fanar spec; added
  `api-spec/openapi.yaml`, its YAML twin (JSON remains normative).

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
- **Sample apps** (cloneable, not shipped as release artifacts) —
  `fanar-spring-boot-4-sample` and `fanar-spring-ai-sample`. Run with
  `FANAR_API_KEY=… ./mvnw -pl <module> spring-boot:run`.
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

[Unreleased]: https://github.com/omahjoub/fanar-java/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/omahjoub/fanar-java/releases/tag/v0.2.0
[0.1.0]: https://github.com/omahjoub/fanar-java/releases/tag/v0.1.0
