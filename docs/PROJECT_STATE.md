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

Unreleased on `main` (0.5.0-SNAPSHOT): one bug fix — `FanarContentFilterException.filterType()` was
dead public API (the error-envelope parser dropped the spec's `type` member, so the accessor could
only ever return `null`) and is now wired on both construction sites, proved through the public API
by `FanarClientErrorEnvelopeIntegrationTest` ([ADR-006](adr/006-unchecked-exception-hierarchy.md)
amendment 2026-09-15). Two items from the 0.4.0 plan were deliberately
left out and carry into the next cycle: the live-suite budget hygiene + nightly run (parked 2026-08-30
pending a higher-quota API key requested from the Fanar team — if granted, only the nightly remains
worth doing) and Maven Central readiness (blocked on the `qa.fanar` namespace, a Fanar-team question
too; fallback `io.github.omahjoub`).

## Planned

- **Maven Central publication** — Sonatype account, GPG signing, release workflow, version-bump policy. (Intro email to the Fanar team sent 2026-05-01; awaiting Sonatype-path pointer.)
- **Readable request-validation errors** — Fanar returns two error shapes. App-level errors use the documented `{"error":{…}}` envelope; **request-validation failures bypass it entirely** and return FastAPI's `{"detail":[{"loc":["body","model"],"msg":"Input should be …"}]}` (observed 2026-09-15, [ledger](WIRE_OBSERVATIONS.md#error-envelope-shape)). The SDK routes those correctly by HTTP status, but the exception message is the raw JSON blob. Parsing `detail[].loc` / `msg` into a readable message — and deciding whether the offending field gets a typed surface — is the natural home for the `param` question below.
- **Error-envelope `param` on the public API** — parsed into the internal envelope as of 0.5.0, deliberately not surfaced: it belongs on `Error`, i.e. on *every* exception, so a `FanarException.param()` accessor means new constructor overloads down all fifteen leaf subtypes. The 2026-09-15 probe argues against ever paying that: `param` is `null` on every envelope captured, and the errors that *would* name a field don't use the envelope at all. If a typed surface is ever wanted, take it from FastAPI's `loc` in the item above, or use an observation attribute (ADR-026's precedent).
- **Spring Boot 3 starter** — `fanar-spring-boot-3-starter` with the Jackson 2 codec; mechanical port of the SB4 starter.
- **LangChain4j adapter** — `fanar-langchain4j` exposing the equivalent of Spring AI's adapters against LangChain4j's `ChatLanguageModel`.
- **Quarkus extension** — CDI beans, build-time wiring, native-image friendliness.
- **`ChatModel` constants for three undocumented ids** — the live server accepts `Fanar-Agentic`, `Fanar-Sadiq-Agentic` and `Islamic-RAG`, none of which is in the spec's `ChatCompletionLLM` enum or in `ChatModel.KNOWN` (observed 2026-09-15 off a validation error that enumerates the accepted set). `ChatModel.of(…)` already works for them (ADR-015 open value record), so this is a missing-constant gap, not a blocker; none appears in `/v1/models` for our key and none has been called. Shipping constants means the usual grid — both codecs, reachability metadata, KNOWN-count assertions.
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
