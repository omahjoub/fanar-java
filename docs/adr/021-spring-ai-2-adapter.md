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
3. **Tracking a moving target.** Spring AI 2.0 was in milestone phase when this adapter was
   first written; it is GA now, and the reactor pins the GA line.

## Decision

- **Layer on top of the SB4 starter.** `fanar-spring-ai-starter` declares
  `fanar-spring-boot-4-starter` as a compile dep; the adapter beans are
  `@ConditionalOnBean(FanarClient.class)`. That mirrors how `spring-ai-starter-model-openai`
  wraps an internally-wired `OpenAiApi`. One dep gets the user a `ChatModel` bean; configuration
  is shared (`fanar.api-key=…`); no duplication of `FanarProperties`.
- **Implement four of the six Spring AI model SPIs.** `ChatModel` (with streaming), `ImageModel`,
  `TextToSpeechModel`, `TranscriptionModel`. Skip the other two:
  - `ModerationModel` — Fanar's moderation returns continuous `safety` + `culturalAwareness`
    scores; Spring AI's `Categories` is a fixed set of boolean flags (19 in Spring AI 2.0.1). A best-effort mapping would always
    report `Categories.isHate()=false`, which is misleading. Surface via
    `FanarClient.moderations()` directly.
  - `EmbeddingModel` — Fanar exposes no `/v1/embeddings` endpoint. RAG users bring their own
    embedder (`spring-ai-openai`, `spring-ai-transformers`, etc.) — the framework's RAG advisors
    work fine with a mixed-vendor setup.
- **Pin the version exactly, in one place** — `spring-ai.version` in the reactor POM, never a
  version range. The adapter tracks one Spring AI version at a time, so a bump is one edit followed
  by one test run, and a build is reproducible from its commit. This was written while 2.0 was still
  in milestones, when the risk was an API shifting between M-releases; the line is GA now and
  resolves from Maven Central like everything else, which removes the need for a milestone
  repository.
- **Tool calling and structured output: degrade silently.** Fanar's chat endpoint gives user
  `tools` / `tool_choice` no effect. Worth being precise about *how*, because this record originally
  said "rejects" — inferred from the spec declaring no such fields, never put on the wire. Live, the
  endpoint **accepts both and silently ignores them**: HTTP 200, `tool_calls` empty, the tool name
  absent from the body, and `prompt_tokens` identical with and without the array, so the field is
  discarded at the edge and never reaches the model. The spec agrees in hindsight —
  `ChatCompletionRequest` declares no `additionalProperties`, i.e. Pydantic's default
  `extra="ignore"`.
  That correction does not change the decision, but it changes which side of the wire the failure
  is visible on, and so validates it: the adapter drops tools *client-side* rather than forwarding
  them, so it never depended on the server refusing anything. Had it forwarded them, a caller would
  get a silent no-op indistinguishable from a model choosing not to call a tool. The chat adapter drops `ToolResponseMessage` from outbound prompts
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
- **Track Spring AI with a version range.** *Rejected*: two builds of the same commit could resolve
  different Spring AI versions, so a green CI run would stop being evidence about the commit. An
  exact pin trades flexibility for reproducibility, and JLBP-14 forbids ranges anyway.

## Consequences

- ✅ Memory, RAG, prompt templates, structured-output converters, advisors all compose on top of
  our adapter — no work duplicated.
- ✅ One dep gets the user four working Spring AI beans.
- ⚠ Coupled to the Spring AI 2.0 line; each release may need an adapter PR. The adapter is a
  single package of thin mapping code with no logic of its own, so the cost is bounded — but it is
  not trivial, and the version is pinned in one place (the reactor's `spring-ai.version`) so a bump
  is one edit.
- ⚠ `ModerationModel` and `EmbeddingModel` consumers won't find Fanar in the Spring AI provider
  catalog for those slots. Documented explicitly in `COMPATIBILITY.md` and the package-info.

## References

- [`fanar-spring-ai-starter`](../../spring-ai-starter/) — the module.
- [Spring AI ChatModel reference](https://docs.spring.io/spring-ai/reference/api/chatmodel.html).
- ADR-020 — Spring Boot 4 starter (this sits on top of it).
- [`COMPATIBILITY.md`](../COMPATIBILITY.md) §3 — full deferred-with-rationale list.
- [`WIRE_OBSERVATIONS.md`](../WIRE_OBSERVATIONS.md) — the live evidence for the tool-calling
  behaviour above. Still open there: `Fanar-Agentic` and `Fanar-Sadiq-Agentic`, which the server
  accepts but gates for our key. If an agentic variant does honour user tools, this record,
  ADR-024 and ADR-030 are the ones to revisit.
