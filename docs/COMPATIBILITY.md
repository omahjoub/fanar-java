# Compatibility Matrix — the lighthouse

The north star for every design decision in this SDK. Three beats, in order:

1. **What Fanar offers** — the capabilities the Fanar API exposes today.
2. **What the core SDK provides on top** — a narrow, strongly-typed, pluggable, observable transport over those capabilities.
3. **What downstream modules will add** — memory, templating, vector stores, structured-output post-processing, evaluation, ecosystem wiring — everything that plugs into the core through stable extension points.

The core stays **universal**: no hard dependency on any framework, no JSON-library lock-in, no HTTP-client lock-in, no observability-vendor lock-in. Every seam is a seam for a reason.

**Legend** — ✅ native &nbsp;·&nbsp; 🟡 partial / conditional &nbsp;·&nbsp; ❌ not supported &nbsp;·&nbsp; ⭐ Fanar-exclusive

---

## 1. What Fanar offers

### Capabilities

| Capability                      | Status | How it's exposed                                                                                          |
|---------------------------------|:------:|-----------------------------------------------------------------------------------------------------------|
| Chat completions                |   ✅   | `POST /v1/chat/completions` across the Fanar chat model family (router + task-specific models)            |
| Sampling controls               |   ✅   | `temperature`, `top_p`, `top_k`, `min_p`, `frequency_penalty`, `presence_penalty`, `repetition_penalty`, `stop`, `max_tokens`, `min_tokens`, `logit_bias`, `stop_token_ids` |
| Beam search                     |   🟡   | `best_of`, `length_penalty`, `early_stopping` — sampling-mode dependent                                   |
| Log-probabilities               |   ✅   | `logprobs`, `top_logprobs`, `prompt_logprobs`                                                             |
| Multiple completions            |   ✅   | `n > 1` returns multiple choices                                                                          |
| Streaming                       |   ✅   | SSE with a typed discriminated union: token · tool-call · tool-result · progress ⭐ · done · error         |
| Tokenization                    |   ✅   | `POST /v1/tokens` — token count and `max_request_tokens` per model                                        |
| Retrieval-Augmented Generation  | ✅ ⭐ | Native via `Fanar-Sadiq` — Islamic-only, with authenticated source references (details below)              |
| Moderation                      | ✅ ⭐ | `POST /v1/moderations` — returns a safety score **and** a cultural-awareness score                         |
| Thinking / reasoning            | 🟡 ⭐ | Two coexisting protocols (flag + first-class message roles) + `reasoning_tokens` accounted in usage        |
| Tool calls (client-declared)    |   🟡   | The stream emits tool-call and tool-result events, but the request has no `tools` / `tool_choice` parameter — tool invocation is server-initiated only |
| Error model                     |   ✅   | Typed `ErrorCode` enum aligned with HTTP status (content-filter, rate-limit, exceeded-quota, no-longer-supported, …) |
| Structured output (JSON schema) |   ❌   | No `response_format` / `json_schema` parameter                                                            |
| Seed / reproducibility          |   ❌   | No `seed` parameter                                                                                        |
| Embeddings                      |   ❌   | No `/v1/embeddings` endpoint — hard gap                                                                    |
| Fine-tuning                     |   ❌   | Not exposed                                                                                                |
| Model Context Protocol (MCP)    |   ❌   | Not supported                                                                                              |

### Modalities

| Modality                 | In | Out | How it's exposed                                                                                           |
|--------------------------|:--:|:---:|------------------------------------------------------------------------------------------------------------|
| Text                     | ✅ | ✅  | `text` content parts in chat messages                                                                      |
| Image (vision)           | ✅ | —   | `image_url` user-content part — Arabic-calligraphy-aware ⭐                                                |
| Video                    | ✅ | —   | `video_url` user-content part — first-class type ⭐                                                        |
| Image generation         | —  | ✅  | `POST /v1/images/generations` — base64 payload                                                             |
| Text-to-Speech           | —  | ✅  | `POST /v1/audio/speech` — includes Quranic TTS with validated reciters ⭐                                  |
| Speech-to-Text           | ✅ | —   | `POST /v1/audio/transcriptions` — short + long-form, speaker-diarized segments, `text` / `srt` / `json`   |
| Audio in chat output     | —  | 🟡  | Assistant response may contain `audio_url` content parts                                                   |
| Voice cloning ⭐         | ✅ | ✅  | `POST/GET/DELETE /v1/audio/voices` — register a named personalized voice from a WAV sample + transcript    |
| Machine translation      | ✅ | ✅  | `POST /v1/translations` — EN↔AR, with HTML/whitespace-preserving preprocessing ⭐                          |
| Poetry generation ⭐     | —  | ✅  | `POST /v1/poems/generations` — dedicated Arabic-poetry model                                               |

### What makes Fanar Fanar ⭐

Signals with **no counterpart** in the generic LLM vocabulary — the reason this SDK is more than a thin OpenAI-compatible client:

- **Islamic RAG** — `message.references[]` = `{number, source, content}`; sources include `quran`, `tafsir`, `sunnah`, `dorar`, `islamweb*`, `islam_qa`, `islamonline`, `shamela`.
- **Scope knobs** for the RAG model — by book (`book_names`), by source (`preferred_sources` / `exclude_sources` / `filter_sources`), and a `restrict_to_islamic` guardrail that rejects non-Islamic prompts server-side.
- **Bilingual progress events** mid-stream — `ProgressChunk.progress.message = {en, ar}`.
- **Cultural-awareness moderation score**, separate from the standard safety score.
- **Quranic TTS with validated reciters** — `quran_reciter ∈ {abdul-basit, maher-al-muaiqly, mahmoud-al-husary}`; the endpoint may return an `X-Revised-Input` header when the recitation text was normalized.
- **Two thinking protocols** — the `enable_thinking` flag and the role-based `thinking` / `thinking_user` protocol coexist.
- **Refusal content part** — assistants can return structured `refusal` parts, not only filtered errors.
- **Translation preprocessing modes** — `default`, `preserve_html`, `preserve_whitespace`, `preserve_whitespace_and_html`.

---

## 2. What the core SDK provides on top

A **strong, universal foundation** over everything in §1. Nothing more.

- **Typed, immutable models** for every endpoint, the SSE chunk union, `references[]`, both thinking protocols, and the model enum. Zero `Map<String, Object>` in public API.
- **Pluggable interceptor / middleware chain** — auth, retry, rate-limit, logging, caching, custom links. Every link replaceable, chain order explicit, Chain-of-Responsibility inside.
- **Pluggable observability SPI** — metrics + tracing through vendor-neutral interfaces. The core never imports a concrete vendor.
- **Transport and (de)serialization as seams** — no hard dependency on one JSON library or one HTTP client; swap either without forking.
- **Stable extension points** — if a downstream module ever has to fork the core to plug in, we designed the core wrong.
- **Internals are not a contract** — code under `qa.fanar.core.internal.*` can be refactored, replaced, or removed in any release without breaking downstream modules. Only the top-level API package and `.spi` surface are stability contracts. The module boundary enforces this.

---

## 3. What downstream modules will add

The core is intentionally narrow. Everything that is *not* Fanar's wire protocol — but that production callers still need — lives in downstream modules that plug in through the core's extension points:

- **Conversation / chat memory**
- **Prompt templating**
- **Structured-output post-processing** (compensating for the ❌ JSON-schema gap in §1)
- **Vector stores and retrieval pipelines** (compensating for the ❌ embeddings gap in §1 with a user-chosen provider)
- **Evaluation harnesses**
- **Ecosystem wiring** — whatever idiomatic configuration, bean/CDI binding, or native-image metadata each target JVM framework expects
- **Provider adapters** that expose Fanar in the idiom of each major JVM AI framework

Their *names, scope and shape* get decided when each module is actually built — not now. Build the core right and each downstream module becomes a thin adapter: a weekend, not a rewrite.

> **Design principles** — small surface, clear seams, Java idioms first (sealed interfaces for unions, records for DTOs, builders for ergonomics, SPIs for extensibility), no leakage of internal choices into public API, **anticipate the future, don't specify it**.

---

*Sources: [`api-spec/openapi.json`](../api-spec/openapi.json) · [`docs/ARCHITECTURE.md`](ARCHITECTURE.md)*
