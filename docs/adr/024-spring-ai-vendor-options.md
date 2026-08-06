# ADR-024 — Spring AI vendor options (`FanarChatOptions` family)

- **Status**: Accepted
- **Date**: 2026-08-06
- **Deciders**: @omahjoub

## Context

Spring AI's portable options interfaces deliberately carry only the lowest-common-denominator
knobs: `ChatOptions` has 8 getters, `TextToSpeechOptions` 4, `ImageOptions` 6. Fanar's chat
endpoint alone accepts ~25 more parameters — persona, madhab, `enable_thinking`,
`restrict_to_islamic`, the Islamic-RAG scoping lists, `logit_bias`, and the vLLM sampling
knobs — and the audio/image endpoints have `with_emotion` / `quran_reciter` / `revise`. Until
0.2.0 none of these were reachable through the Spring AI surface; users had to drop down to the
auto-wired `FanarClient` bean.

Every major Spring AI provider solves this the same way: a provider-specific options class
implementing the portable interface (e.g. `OpenAiChatOptions`), which the model adapter narrows
via `instanceof`.

## Decision

Three immutable, builder-based options classes in `qa.fanar.spring.ai`:

1. **`FanarChatOptions implements ChatOptions`** — the 8 portable fields plus every
   `ChatRequest` knob portable options lack. Typed with core value classes (`Madhab`,
   `BookName`, `Source`) rather than raw strings.
2. **`FanarTextToSpeechOptions implements TextToSpeechOptions`** — portable fields plus
   `withEmotion` and `quranReciter` (the latter was previously hardcoded `null` in the adapter).
3. **`FanarImageOptions implements ImageOptions`** — portable fields plus `revise`.

Merge semantics are **portable-first**: the adapters keep mapping the portable getters for any
`*Options` implementation, then apply the Fanar extras only when the instance is the Fanar
subtype (`instanceof` narrowing), and only for non-null fields. A non-Fanar options instance
behaves exactly as before. Model-specific validation stays server-side (ADR-015); the options
classes perform none.

`FanarChatOptions.Builder` **extends Spring AI's `DefaultChatOptionsBuilder`** — this matters
because `ChatClient.prompt(Prompt)` rebuilds request options via `options.mutate()…build()`.
Our `mutate()` returns the Fanar builder pre-populated with *all* fields, so the extras survive
the fluent pipeline instead of being silently flattened to portable options. The
`combineWith(...)` override merges the extras when both builders are Fanar builders (non-null
values from the other builder win; extras collections replace rather than concatenate — they
are filters, and appending two filters is not a meaningful union — while the portable fields
keep Spring AI's own merge rules, including stop-sequence concatenation).

## Alternatives considered

- **Do nothing (core-client escape hatch only).** *Rejected by scope decision for 0.2.0*: the
  new spec capabilities (persona, madhab, emotion, revise) deserve first-class reach from the
  framework surface users actually hold.
- **String-typed extras** (mirroring how some providers expose raw maps). *Rejected*: the SDK
  already owns typed open value classes; dropping to strings at the framework boundary throws
  away compile-time safety for no interop gain.
- **A generic `Map<String, Object> extraBody`.** *Rejected*: undiscoverable, untyped, and
  bypasses `ChatRequest` validation.
- **Registering the options as auto-configured default beans.** *Rejected for now*: defaults
  remain constructor arguments on the model adapters; per-call options are the Spring AI idiom.

## Consequences

### Positive
- Full framework-level parity with the Fanar wire surface; no more dropping to `FanarClient`
  for provider knobs.
- The `instanceof` pattern matches the wider Spring AI ecosystem — no new mental model.

### Negative / Trade-offs
- `FanarChatOptions` is a wide class (~32 fields) that must track `ChatRequest` — a new
  `ChatRequest` knob now lands in two places. Accepted: both live in this repo and the codec
  wire tests catch drift.
- Spring AI's `ChatClient` merging utilities only understand portable fields; Fanar extras
  survive only when the Fanar options instance itself reaches the model (the normal path).

### Neutral
- Properties-file binding (`spring.ai.*`-style option defaults) is not provided; options are
  programmatic. Revisit if users ask.

## References

- ADR-015 Hand-written DTO conventions (validation stays server-side / request-side)
- ADR-021 Spring AI 2.0 adapter
- ADR-023 Streaming TTS via `Flow.Publisher<byte[]>`
- Spring AI `OpenAiChatOptions` — the ecosystem precedent
