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

## 3. What downstream modules add

Two layers sit on top of the core: **observability adapters** (one HTTP-call's worth of cross-cutting concern, opt-in via the `ObservabilityPlugin` SPI) and **framework adapters** (idiomatic wiring + provider bindings into a JVM AI framework).

### Observability — shipped

| Adapter | Module | What it produces |
|---|---|---|
| SLF4J | `fanar-obs-slf4j` | One structured log line per operation; `DEBUG` on success, `ERROR` on failure. |
| OpenTelemetry | `fanar-obs-otel` | One OTel span per operation; W3C `traceparent` propagation; survives virtual-thread async hops. |
| Micrometer | `fanar-obs-micrometer` | One `Observation` per operation; metric tags from low-cardinality attributes; backend wired by the consuming app's `ObservationRegistry`. |

Compose any combination via `ObservabilityPlugin.compose(slf4j, otel, micrometer)` — single slot, fan-out semantics. None ship a `ServiceLoader` descriptor, so adding the jar to the classpath does not silently change the `FanarClient` default of `ObservabilityPlugin.noop()`.

### Framework adapters — shipped

| Adapter | Module | What it adds |
|---|---|---|
| Spring Boot 4 | `fanar-spring-boot-4-starter` | `@AutoConfiguration` + typed `fanar.*` properties + auto-wired `Interceptor` / `ObservabilityPlugin` beans + `FanarHealthIndicator` (when `spring-boot-health` is on the classpath). Wire-logging interceptor enabled via `fanar.wire-logging.level`. |
| Spring AI 2.0 | `fanar-spring-ai-starter` | `ChatModel` + `StreamingChatModel` + `ImageModel` + `TextToSpeechModel` + `TranscriptionModel` adapters layered on top of the SB4 starter. Memory + RAG advisors compose via Spring AI's `ChatClient`; we don't expose memory primitives in core. |

### Framework adapters — planned

- **Spring Boot 3** — Jackson 2 codec, mechanical port of the SB4 starter.
- **LangChain4j** — `ChatLanguageModel` provider binding; same shape as the Spring AI adapter.
- **Quarkus** — CDI beans, build-time wiring, native-image friendliness.

### Deferred — Spring AI gaps with rationale

- **`ModerationModel`** — Fanar returns continuous `safety` + `culturalAwareness` scores; Spring AI's surface expects 16 category booleans (`Categories.isHate()` etc.). A best-effort mapping would always report all categories `false`, misleading consumers. Use `FanarClient.moderations()` directly.
- **`EmbeddingModel`** — Fanar has no embeddings endpoint at all (the ❌ in §1 above). RAG users bring their own embedder (`spring-ai-openai`, `spring-ai-transformers`, etc.).
- **Native chat structured output** — Fanar exposes no `response_format` field. Spring AI's prompt-engineering converters (`BeanOutputConverter`) still work end-to-end since they shape the prompt text, not a model flag.
- **User-supplied tool calling** — Fanar rejects user `tools` / `tool_choice` server-side. Spring AI's tool-callback advisors degrade silently in our adapter (we drop `ToolResponseMessage` from outbound prompts and never emit `tool_calls` to consumers).

### What still belongs downstream

- **Conversation / chat memory** — owned by Spring AI / LangChain4j, not core. We expose the model SPI; the framework's advisors persist history.
- **Prompt templating** — same.
- **Structured-output post-processing** — same; framework concern that calls into our typed model.
- **Vector stores + retrieval pipelines** — same; pair our adapter with the framework's RAG advisor and a user-chosen embedder.
- **Evaluation harnesses** — out of scope.

> **Design principles** — small surface, clear seams, Java idioms first (sealed interfaces for unions, records for DTOs, builders for ergonomics, SPIs for extensibility), no leakage of internal choices into public API, **anticipate the future, don't specify it**.

---

*Sources: [`api-spec/openapi.json`](../api-spec/openapi.json) · [`docs/ARCHITECTURE.md`](ARCHITECTURE.md)*
