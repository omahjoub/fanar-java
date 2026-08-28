# Project state

> **Snapshot — 2026-08-28.** Updated on every milestone. If this looks wrong or stale, that is
> the signal — update it in the same PR as whatever moved.

## Phase

**0.2.0 released 2026-08-06** ([v0.2.0](https://github.com/omahjoub/fanar-java/releases/tag/v0.2.0),
GitHub Release, 10 artifacts); `main` is at `0.3.0-SNAPSHOT`. The release delivers full parity
with the 2026-08 Fanar spec (`openapi.json` normative + `openapi.yaml` twin): `Fanar-Sadiq-2` +
`persona` + `madhab`, streamed + emotional TTS, rich voice catalogue, image prompt revision,
the 499 `client_closed_request` error, envelope-code error routing, and Spring AI vendor
options — three documented breaking changes under ADR-019. All shipping modules hold the
100 % JaCoCo gate. Pre-1.0; no Maven Central artifacts yet. Release process:
[docs/RELEASING.md](RELEASING.md).

Unreleased on `main` (0.3.0-SNAPSHOT): the 2026-08-27 spec refresh (doc-only — it adds the
rate-limit response-header contract) and the retry fix it exposed — HTTP-status retry never fired
through 0.2.0 because the facades mapped errors *after* the interceptor chain; mapping now happens
at the retry boundary inside the chain, with `Retry-After` semantics per ADR-025 and a
`fanar.retry.max-delay` knob in the starter. See [CHANGELOG](../CHANGELOG.md).

## Shipped

| Layer | Module(s) | Highlights |
|---|---|---|
| Core SDK | `fanar-core` | `FanarClient` + 8 typed domain facades (chat / models / tokens / moderations / translations / poems / images / audio). Sealed `FanarException` hierarchy (14 subtypes; mapper routes by envelope `error.code` with HTTP-status fallback, at the retry boundary inside the interceptor chain; both 429 subtypes carry `retryAfter()`). SSE streaming via `Flow.Publisher<StreamEvent>` + streamed TTS via `Flow.Publisher<byte[]>` (ADR-023). Sync + async + streaming. 2026-08 spec parity: `Fanar-Sadiq-2`, `persona`, `madhab`, `with_emotion`, rich `AvailableVoice` catalogue, image `revise`/`revised_prompt`. 100 % JaCoCo. |
| JSON codecs | `fanar-json-jackson2`, `fanar-json-jackson3` | Snake-case wire format, NON_NULL inclusion, six flattening deserializers, generic wire-value module (18 value classes incl. `Madhab`, `VoiceType`), `ServiceLoader` discovery, GraalVM reachability metadata. |
| Observability | `fanar-obs-slf4j`, `fanar-obs-otel`, `fanar-obs-micrometer` | One adapter per backend; opt-in (no `ServiceLoader`). `ObservabilityPlugin.compose(...)` factory wires multiple adapters into a single slot. |
| Interceptors | `fanar-interceptor-logging` | OkHttp-style level ladder (`NONE` / `BASIC` / `HEADERS` / `BODY`), SLF4J sink at `fanar.wire`, redaction, body cap, streaming-aware. |
| Live tests | `fanar-java-e2e` | Parameterized over both codecs. 21 chat-completion shapes × 2 codecs (incl. persona + gated Sadiq-2 madhab) + every other domain (audio TTS incl. streaming + emotion, STT on one shared clip, voices, images incl. revision, translations, moderations, tokens, models, poems), plus a transport-level `LiveRateLimitHeadersTest` (single codec) pinning the rate-limit header contract. Gated on `FANAR_API_KEY`. |
| GraalVM | `fanar-java-e2e-graalvm` | Fat-jar + `native-image` profile. Self-test mode (offline: 9 decode + 9 encode probes + obs plugins + interceptor) and live mode covering every domain. CI: PR-time native-smoke + workflow-dispatch metadata bootstrap. |
| Spring Boot 4 | `fanar-spring-boot-4-starter`, `fanar-spring-boot-4-sample` | `@AutoConfiguration` + typed `FanarProperties` record (incl. `fanar.retry.*` with `max-delay`) + replaceable `FanarJsonCodec` / `RetryPolicy` beans + auto-wired `Interceptor` / `ObservabilityPlugin` beans + `FanarHealthIndicator` (Actuator). Sample app exercises the wiring end-to-end. |
| Spring AI 2.0 | `fanar-spring-ai-starter`, `fanar-spring-ai-sample` | `ChatModel` (real token streaming) + `ImageModel` (revision metadata + `created`) + `TextToSpeechModel` (real chunk streaming) + `TranscriptionModel` adapters, plus vendor options `FanarChatOptions` / `FanarTextToSpeechOptions` / `FanarImageOptions` (ADR-024). Sample uses `ChatClient` with `MessageChatMemoryAdvisor` for multi-turn. Spring AI `2.0.0`. |
| Build / CI | parent POM, `.github/workflows/ci.yml` | Java 21 + 25 matrix, JaCoCo 100 % gate on every shipping module, `dependency:analyze` strict mode, doclint at javac time, JaCoCo report uploaded as artifact on failure for flake diagnosis, `-parameters` flag enabled globally. |

## Planned

- **Maven Central publication** — Sonatype account, GPG signing, release workflow, version-bump policy. (Intro email to the Fanar team sent 2026-05-01; awaiting Sonatype-path pointer.)
- **Spring Boot 3 starter** — `fanar-spring-boot-3-starter` with the Jackson 2 codec; mechanical port of the SB4 starter.
- **LangChain4j adapter** — `fanar-langchain4j` exposing the equivalent of Spring AI's adapters against LangChain4j's `ChatLanguageModel`.
- **Quarkus extension** — CDI beans, build-time wiring, native-image friendliness.
- **Nightly live e2e on CI** — scheduled job runs `fanar-java-e2e` with `FANAR_API_KEY` injected as a secret; PR builds stay offline. Budget constraint (observed 2026-08-28): the TTS models allow 20 requests/day and a full run spends 14, so the nightly must be the only full run that day on that key.

## Deferred (won't fit cleanly)

- **Spring AI `ModerationModel`** — Fanar's `/v1/moderations` returns continuous `safety` + `culturalAwareness` scores; Spring AI's surface expects 16 category booleans. A best-effort mapping would always report `Categories.isHate()=false`, which is misleading. Surfaced via `FanarClient.moderations()` directly instead.
- **Spring AI `EmbeddingModel`** — Fanar exposes no `/v1/embeddings` endpoint at all. Users wanting RAG bring their own embedder (`spring-ai-openai`, `spring-ai-transformers`, etc.).
- **Native `response_format` / structured output on chat** — not in the Fanar wire spec. Spring AI's prompt-engineering converters (`BeanOutputConverter`) still work because they shape the prompt text, not the request flag.
- **User-supplied tool calling** — Fanar's `/v1/chat/completions` rejects user `tools` / `tool_choice`. The `tool_calls` events in streams are server-internal Sadiq retriever telemetry. Spring AI tool callbacks degrade silently in our adapter.
- **Fanar `stop` parameter** — silently dropped server-side; documented in tests.
- **Typed rate-limit header exposure** — the 2026-08-27 spec documents `x-ratelimit-limit` / `-remaining` / `-reset` and `ratelimit-policy` on every rate-limited 2xx, but the SDK surfaces neither as DTO fields nor as observation attributes; only the `Retry-After` hint is typed (on both 429 exceptions). Which surface is right — exception fields, response metadata, `fanar.ratelimit.*` attributes, a proactive throttle — needs an ADR, deferred until a consumer needs more than the `Interceptor` SPI (which sees the raw headers today; `LiveRateLimitHeadersTest` shows the pattern).

## Cadence for updates

Update this file when:

- A milestone ships (new module, new framework adapter, version-tag, public release).
- An ADR gets superseded.
- A `Planned` item moves to `Shipped`, or a `Deferred` item gains traction.

Commit the update in the same PR as the change that motivated it — never separately.
