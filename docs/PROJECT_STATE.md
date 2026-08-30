# Project state

> **Snapshot — 2026-08-30.** Updated on every milestone. If this looks wrong or stale, that is
> the signal — update it in the same PR as whatever moved.

## Phase

**0.4.0 released 2026-08-30** ([v0.4.0](https://github.com/omahjoub/fanar-java/releases/tag/v0.4.0),
GitHub Release, 10 artifacts) — "proof over coverage". Every behaviour an ADR promises to a consumer
is proved through the public API by a named seam-crossing `*IntegrationTest` (a rule in CONTRIBUTING,
backed by the unpublished `test-support` fixture); what the live API actually does is recorded, dated
and pinned by tests, in the [wire-observations ledger](WIRE_OBSERVATIONS.md); the wire log keeps
failures; the retry boundary publishes the server's rate-limit window (`fanar.ratelimit.*` attributes,
`RateLimitInfo` on both 429s — ADR-026) and stops sleeping past a total budget (`maxTotalDelay`,
`RetryPolicy.builder()`, `fanar.retry.max-total-delay` — ADR-027, the release's one breaking change);
the eight facades share one internal `Dispatcher`. See [CHANGELOG](../CHANGELOG.md).

Unreleased on `main` (0.5.0-SNAPSHOT) — nothing yet. Two items from the 0.4.0 plan were deliberately
left out and carry into the next cycle: the live-suite budget hygiene + nightly run (parked 2026-08-30
pending a higher-quota API key requested from the Fanar team — if granted, only the nightly remains
worth doing) and Maven Central readiness (blocked on the `qa.fanar` namespace, a Fanar-team question
too; fallback `io.github.omahjoub`).

## Planned

- **Maven Central publication** — Sonatype account, GPG signing, release workflow, version-bump policy. (Intro email to the Fanar team sent 2026-05-01; awaiting Sonatype-path pointer.)
- **Spring Boot 3 starter** — `fanar-spring-boot-3-starter` with the Jackson 2 codec; mechanical port of the SB4 starter.
- **LangChain4j adapter** — `fanar-langchain4j` exposing the equivalent of Spring AI's adapters against LangChain4j's `ChatLanguageModel`.
- **Quarkus extension** — CDI beans, build-time wiring, native-image friendliness.
- **Nightly live e2e on CI** — scheduled job runs `fanar-java-e2e` with the `FANAR_API_KEY` secret (it exists; today only `graalvm.yml`'s manual bootstrap job uses it); PR builds stay offline. Parked 2026-08-30 pending a higher-quota key from the Fanar team: on the standard key a full run spends 11 of `Fanar-Aura-TTS-2`'s 20 per trailing 24 h ([budget table](WIRE_OBSERVATIONS.md#live-suite-budget)), so the nightly would have to be the only full run within 24 h, and it must exclude the six known-gated cases or stay red every night.

## Deferred (won't fit cleanly)

- **Spring AI `ModerationModel`** — Fanar's `/v1/moderations` returns continuous `safety` + `culturalAwareness` scores; Spring AI's surface expects 16 category booleans. A best-effort mapping would always report `Categories.isHate()=false`, which is misleading. Surfaced via `FanarClient.moderations()` directly instead.
- **Spring AI `EmbeddingModel`** — Fanar exposes no `/v1/embeddings` endpoint at all. Users wanting RAG bring their own embedder (`spring-ai-openai`, `spring-ai-transformers`, etc.).
- **Native `response_format` / structured output on chat** — not in the Fanar wire spec. Spring AI's prompt-engineering converters (`BeanOutputConverter`) still work because they shape the prompt text, not the request flag.
- **User-supplied tool calling** — Fanar's `/v1/chat/completions` rejects user `tools` / `tool_choice`. The `tool_calls` events in streams are server-internal Sadiq retriever telemetry. Spring AI tool callbacks degrade silently in our adapter ([wire observations](WIRE_OBSERVATIONS.md)).
- **Fanar `stop` parameter** — silently dropped server-side; dated in the [wire observations](WIRE_OBSERVATIONS.md).
- **Rate-limit headers on response DTOs** — shipped in 0.4.0 as observation attributes and as `RateLimitInfo` on the 429 exceptions instead ([ADR-026](adr/026-rate-limit-telemetry.md)); putting them on every DTO would touch the whole ADR-015 grid for information those two surfaces already carry. A proactive throttle stays a user-supplied interceptor (ADR-012).

## Cadence for updates

Update this file when:

- A milestone ships (new module, new framework adapter, version-tag, public release).
- An ADR gets superseded.
- A `Planned` item moves to `Shipped`, or a `Deferred` item gains traction.

Commit the update in the same PR as the change that motivated it — never separately.
