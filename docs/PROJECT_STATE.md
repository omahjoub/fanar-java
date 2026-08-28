# Project state

> **Snapshot — 2026-08-28.** Updated on every milestone. If this looks wrong or stale, that is
> the signal — update it in the same PR as whatever moved.

## Phase

**0.3.0 released 2026-08-29** ([v0.3.0](https://github.com/omahjoub/fanar-java/releases/tag/v0.3.0),
GitHub Release, 10 artifacts). The release adds the
rate-limit response-header contract and the retry fix it exposed — HTTP-status retry never fired
through 0.2.0 because the facades mapped errors *after* the interceptor chain; mapping now happens
at the retry boundary inside the chain, with `Retry-After` semantics per ADR-025 and a
`fanar.retry.max-delay` knob in the starter. See [CHANGELOG](../CHANGELOG.md).

Unreleased on `main` (0.4.0-SNAPSHOT)

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
