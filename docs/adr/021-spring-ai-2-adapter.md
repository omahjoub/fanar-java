# ADR-021 — Spring AI 2.0 adapter

- **Status**: Accepted
- **Date**: 2026-04-27
- **Deciders**: @omahjoub

## Context

Spring AI's value proposition is the framework-layer machinery around model calls — chat memory,
RAG advisors, prompt templates, structured-output converters, the `ChatClient` fluent surface.
Once we expose the model SPIs (`ChatModel`, `ImageModel`, `TextToSpeechModel`,
`TranscriptionModel`), all of that composes for free. Spring AI 2.0 is the milestone aligned
with Spring Boot 4 / Spring Framework 7, so it's the right pin for the SB4 era.

Three open questions:

1. **Layering.** Sit on top of `fanar-core` directly, or on top of `fanar-spring-boot-4-starter`?
2. **Mismatched model layers.** Spring AI ships six model SPIs; Fanar's API doesn't fit all of
   them.
3. **Milestone vs GA.** Spring AI 2.0 is still in milestone phase (currently 2.0.0-M4).

## Decision

- **Layer on top of the SB4 starter.** `fanar-spring-ai-starter` declares
  `fanar-spring-boot-4-starter` as a compile dep; the adapter beans are
  `@ConditionalOnBean(FanarClient.class)`. That mirrors how `spring-ai-starter-model-openai`
  wraps an internally-wired `OpenAiApi`. One dep gets the user a `ChatModel` bean; configuration
  is shared (`fanar.api-key=…`); no duplication of `FanarProperties`.
- **Implement four of the six Spring AI model SPIs.** `ChatModel` (with streaming), `ImageModel`,
  `TextToSpeechModel`, `TranscriptionModel`. Skip the other two:
  - `ModerationModel` — Fanar's moderation returns continuous `safety` + `culturalAwareness`
    scores; Spring AI's surface expects 16 category booleans. A best-effort mapping would always
    report `Categories.isHate()=false`, which is misleading. Surface via
    `FanarClient.moderations()` directly.
  - `EmbeddingModel` — Fanar exposes no `/v1/embeddings` endpoint. RAG users bring their own
    embedder (`spring-ai-openai`, `spring-ai-transformers`, etc.) — the framework's RAG advisors
    work fine with a mixed-vendor setup.
- **Pin the milestone exactly.** `<spring-ai.version>2.0.0-M4</spring-ai.version>` (no version
  ranges). Pre-GA APIs can shift between milestones; we re-pin and re-test in one PR when 2.0.0
  GA drops or when a new milestone is required. The `spring-milestones` repository is added to
  the parent POM scoped to milestones only (`releases.enabled=false`) so we never accidentally
  pull a non-GA version of any other artifact.
- **Tool calling and structured output: degrade silently.** Fanar rejects user `tools` /
  `tool_choice` server-side. The chat adapter drops `ToolResponseMessage` from outbound prompts
  and never emits `tool_calls` to consumers — Spring AI's tool-callback advisor sees no tool
  invocations and falls through to the model's text reply. Native `response_format` isn't on the
  Fanar wire spec; Spring AI's `BeanOutputConverter` still works because it shapes the prompt
  text, not a request flag.

## Alternatives considered

- **Layer directly on `fanar-core`, conditional on a user-supplied `FanarClient` bean.** Maximum
  flexibility but worse UX — users would have to add two starters or wire the client manually.
  If demand for non-Boot Spring AI usage materialises, we'd ship a sibling `fanar-spring-ai-core`
  with no auto-config (just the `FanarChatModel` class) for that audience.
- **Best-effort `ModerationModel` mapping.** Always-false category booleans actively mislead.
  Better to be honest about the gap.
- **Track Spring AI milestones with a version range (`[2.0.0-M4,2.0.0)`).** Risks pulling a
  later milestone with breaking API changes between PR builds. Exact pin trades flexibility for
  reproducibility.

## Consequences

- ✅ Memory, RAG, prompt templates, structured-output converters, advisors all compose on top of
  our adapter — no work duplicated.
- ✅ One dep gets the user four working Spring AI beans.
- ⚠ Coupled to a specific milestone. Each Spring AI 2.0.x release likely needs an adapter PR
  until 2.0.0 GA stabilises. The adapter is small (~200 LOC), so the maintenance cost is bounded.
- ⚠ `ModerationModel` and `EmbeddingModel` consumers won't find Fanar in the Spring AI provider
  catalog for those slots. Documented explicitly in `COMPATIBILITY.md` and the package-info.

## References

- [`fanar-spring-ai-starter`](../../spring-ai-starter/) — the module.
- [Spring AI ChatModel reference](https://docs.spring.io/spring-ai/reference/2.0.0-M4/api/chatmodel.html).
- ADR-020 — Spring Boot 4 starter (this sits on top of it).
- [`COMPATIBILITY.md`](../COMPATIBILITY.md) §3 — full deferred-with-rationale list.
