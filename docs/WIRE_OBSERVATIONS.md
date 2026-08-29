# Wire observations — what the Fanar API actually does

> **A ledger, not a spec.** [`api-spec/openapi.json`](../api-spec/openapi.json) is the normative contract. This file
> records where the *live* API was observed to behave differently, or where the spec is silent — dated, with the
> key class it was seen on, and the live test that pins it. When the two disagree, the ledger wins for test
> expectations until a newer observation replaces the row. Consumers: read it before building on a behaviour the
> spec merely implies. Contributors: every new observation is a row here, in the same PR as the test caveat that
> carries it (see [Adding an observation](#adding-an-observation)).

## How to read it

- **Date** — when the behaviour was observed (ISO). An observation that no longer holds stays in the row as history
  (see Diwan): the next reader needs the trajectory, not only the latest state.
- **Key** — everything below was seen with the SDK's standard limited key (the one `FANAR_API_KEY` names in the
  live suite) unless the row says otherwise. Fanar gates models and features per key, so another key can see
  different answers.
- **Pinned by** — the live test (`e2e`, `@Tag("live")`) whose failure would flag a change. `—` means observed by
  hand (curl, wire log) and asserted by nothing: expect it, do not build on it. The seam-crossing
  `*IntegrationTest`s prove *SDK* behaviour against a scripted server and are named by the ADRs, not here
  ([CONTRIBUTING — Testing](CONTRIBUTING.md#testing)).
- **Verbatim** — status codes, envelope codes and header values are quoted as they appeared in the `fanar.wire`
  log (`WireLoggingInterceptor` at `BODY`, which every live client enables via `TestClients.liveWithLogging`).

Pinning test classes, all under [`e2e/src/test/java/qa/fanar/e2e/`](../e2e/src/test/java/qa/fanar/e2e/):
[`LiveChatCompletionsTest`](../e2e/src/test/java/qa/fanar/e2e/chat/LiveChatCompletionsTest.java) (with its
[`Probes`](../e2e/src/test/java/qa/fanar/e2e/Probes.java)),
[`LiveModelsTest`](../e2e/src/test/java/qa/fanar/e2e/models/LiveModelsTest.java),
[`LivePoemsTest`](../e2e/src/test/java/qa/fanar/e2e/poems/LivePoemsTest.java),
[`LiveAudioSpeechTest`](../e2e/src/test/java/qa/fanar/e2e/audio/LiveAudioSpeechTest.java),
[`LiveAudioTranscriptionTest`](../e2e/src/test/java/qa/fanar/e2e/audio/LiveAudioTranscriptionTest.java),
[`LiveAudioVoicesTest`](../e2e/src/test/java/qa/fanar/e2e/audio/LiveAudioVoicesTest.java),
[`LiveImagesTest`](../e2e/src/test/java/qa/fanar/e2e/images/LiveImagesTest.java),
[`LiveTranslationsTest`](../e2e/src/test/java/qa/fanar/e2e/translations/LiveTranslationsTest.java),
[`LiveModerationsTest`](../e2e/src/test/java/qa/fanar/e2e/moderations/LiveModerationsTest.java),
[`LiveTokensTest`](../e2e/src/test/java/qa/fanar/e2e/tokens/LiveTokensTest.java),
[`LiveRateLimitHeadersTest`](../e2e/src/test/java/qa/fanar/e2e/transport/LiveRateLimitHeadersTest.java).

## Chat completions — `POST /v1/chat/completions`

| Date | Observed | Spec says | Consequence / pinned by |
|---|---|---|---|
| 2026-04-25 | `stop` is accepted on the wire and **ignored**: with `stop: ["\n"]` and with `stop: ["6"]` the model ran past both and stopped at `<end_of_turn>` (`finish_reason: stop`). | `stop` — "Up to 4 sequences where the API will stop generating further tokens." | The JSON shape is proved offline (`ChatRequestKnobsTest.stopSequenceSerializesAsJsonArray`); live §3.3 is a round-trip smoke check by design. `stop_token_ids` (numeric) is untried. Pinned by `LiveChatCompletionsTest.sampling_stopSequence`. |
| 2026-08-29 | The response `model` names the **routed backend, not the requested id**: `Fanar` → `Fanar-C-2-27B`, `Fanar-C-1-8.7B` → `fanar-c-1-9b`, `Fanar-S-1-7B` → `/fanar/Fanar-S-1-7B-20241231` (`Fanar-Sadiq` and `Fanar-C-2-27B` echo themselves). Quota windows follow the *requested* id — `Fanar` and `Fanar-C-2-27B` were counted separately. | `model` is the model to use; the response field is not documented as an alias. | `ChatModel` is an open value class, so unfamiliar ids decode; never assert `response.model()` equals the request's. `LiveChatCompletionsTest` §1 asserts `model()` is present, not equal. |
| 2026-04-25 | The request schema has **no `tools` / `tool_choice`**. The `tool_calls` on response messages and stream chunks are the server's own Sadiq retriever calls, already executed (`result` is populated) — never a client round trip. | Schema: 32 top-level request fields, none for tools. | No user function calling on the SDK roadmap until the server grows one; the Spring AI adapter drops tool messages. Decoding of the server-side shape is pinned offline (`AdapterParityTest`); `LiveChatCompletionsTest.streaming_sadiqToolCalls` only observes. |
| 2026-08-06 | **Model gating answers HTTP 422**, envelope `code: "unprocessable"`, message `"Model not authorized"` — seen on `Fanar-Sadiq-2` with `madhab: ["hanafi"]`. The request is accepted up to the gate, so a 422 here is not a wire-format problem. | `Fanar-Sadiq-2` "requires additional authorization and is not allowed by default"; the wording suggests 403 `invalid_authorization`. | Surfaces as `FanarUnprocessableException` by envelope-code routing (ADR-006) — never sniff the message to turn it into `FanarAuthorizationException`. `LiveChatCompletionsTest.conversation_sadiq2WithMadhab` fails loudly until the key is upgraded: expected, not a flake. |
| 2026-04-25 | `enable_thinking=true` on `Fanar-C-2-27B` returns the reasoning **inline in `content` as `<think>…</think>` markup**; a `max_tokens` much under 256 is spent on the reasoning and the answer never lands. Works for the standard key despite the gating note. | "only the Fanar-C-2-27B model supports this parameter with additional authorization". | Budget thinking prompts generously; a structured thinking field on `ChatMessage` is a future SDK item. Pinned by `LiveChatCompletionsTest.conversation_thinking` (no tolerance; not among the known failures below). |
| 2026-04-25, re-confirmed 2026-08-29 | Sadiq `book_names` **constrains citations, not generation**: with `book_names: ["علم البيان"]` (a rhetoric text) the model still summarised Al-Fatihah fluently — only the two references came from that book. 2026-08-29, with `القيم الإسلامية` (`BookName.KNOWN`'s first entry): same fluent answer, both references `القيم الإسلامية [28]`; the unconstrained prompt returned five web / tafsir references. | "List of book names to use for the Fanar-Sadiq model." | A retrieval filter, not a guardrail: enforce corpus mismatch downstream (e.g. reject empty or out-of-set `references`). `LiveChatCompletionsTest.conversation_sadiqWithBookName` asserts wire acceptance only. |
| 2026-04-25 | Sadiq streams **do not always emit `ProgressChunk`s** (depends on whether retrieval ran). Not observable in the wire log — SSE bodies are not captured. | Progress events are part of the documented stream union. | Never assert their presence. `LiveChatCompletionsTest.streaming_sadiqProgressChunks` asserts only a clean `DoneChunk` termination. |
| 2026-08-29 | Every 2xx carries the four quota headers (`x-ratelimit-limit: 50`, `x-ratelimit-remaining`, `x-ratelimit-reset`, `ratelimit-policy: 50;w=60`), and the window is **sliding, keyed by the requested model id**: `remaining` = 50 − requests in the trailing 60 s, `reset` = seconds until the *oldest* counted request ages out (60 → 59 on consecutive calls; `Fanar-Sadiq` went 2 → 8 when one aged out, `remaining` unchanged at 45). Auth/gate 4xx (401, the Sadiq-2 422) carry no headers and do not count. | Rate-limit header contract (`info.description`, 2026-08-27 refresh): `x-ratelimit-reset` is "seconds until a request slot frees up". | Pinned by `LiveRateLimitHeadersTest.success_carriesRateLimitHeaders` (single codec — reading the headers again would spend a counted request; `reset` asserted to parse only). Full shape in [Transport level](#transport-level-rate-limit-headers-and-x-id). |
| 2026-08-29 | A 401 (bad key) carries **no** rate-limit headers and no `x-id`, and does not count against the model's window. | Headers are documented for "rate-limited responses" only. | Seen in every run through `LiveChatCompletionsTest.error_invalidApiKey`, which asserts only the `FanarAuthenticationException` mapping. |

## Models — `GET /v1/models`

| Date | Observed | Spec says | Consequence / pinned by |
|---|---|---|---|
| 2026-08-06, re-confirmed 2026-08-29 | The listing is **visibility-scoped per key**, not the callable universe: 11 models listed (identical on both dates); absent were `Fanar-Sadiq-2`, `Fanar-Diwan`, `Fanar-Sadiq-TTS-1` and `Fanar-Guard-2` — all spec-listed, and Guard-2 is callable by the same key (`LiveModerationsTest` passes). Model-level gating hides a model; feature-level gating does not (`Fanar-C-2-27B` stays listed while `enable_thinking` is gated). | 15 models in the rate-limit table; nothing says the listing is scoped. | Never use the listing as an availability oracle (the health indicator reports reachability, not capability). `LiveModelsTest.list_returnsAllKnownChatModels` asserts `KNOWN ⊆ listing` **except** `MODEL_GATED`; a newly gated model joins that set with a dated note, never a weaker assertion. |
| 2026-08-29 | 2xx carries **no** rate-limit headers (quota is per model; the listing is not a model call) but does carry `x-id`. | "Every rate-limited response reports your current quota." | Seen in every run (`LiveModelsTest`, no header assertion). A header reader must tolerate absent headers. |

## Poems — `POST /v1/poems/generations` (`Fanar-Diwan`)

| Date | Observed | Spec says | Consequence / pinned by |
|---|---|---|---|
| 2026-04-25 → 2026-08-29 | **History.** 2026-04-25: gated for the key — absent from the listing, calls answered 403 / 504 `timeout`. 2026-08-06: generation **works** (markdown-table poems with full tashkeel, poet / meter / rhyme header) while the model is still absent from `/v1/models`. Latency 6–13 s on 2026-08-06, 13–15 s per poem on 2026-08-29. | 50 requests/minute; no authorization note in the 2026-08 spec (the 2026-04 spec had one; `LivePoemsTest`'s javadoc records the old era). | Listing caveat: see [Models](#models--get-v1models). |
| 2026-08-06, re-confirmed 2026-08-29 | **Nondeterministic verse miss**: identical requests (`"Write a poem about the sea"`) in one run went 422 → 200 → 200 → 422 (2026-08-06) and 422 → 200 → 200 → 422 → 200 → 200 (2026-08-29), the 422 being `code: "unprocessable"` / `"No suitable verses found for the given prompt."`, answered in 5.7–7.3 s. Diwan composes from a verse corpus and sometimes misses a prompt it served moments earlier. The miss is a post-admission 4xx: it carries the quota headers and counts against the 50/min window, but has no `x-id`. | — | The one tolerated *semantic* nondeterminism: `LivePoemsTest` retries **only** `FanarUnprocessableException`, up to 3× per test (`generate_returnsNonEmptyPoem`, `generate_asyncCompletesAgainstLiveInfra`); everything else fails loudly. A single Diwan 422 in an ad-hoc run is not an SDK regression. |

## Audio — speech `POST /v1/audio/speech` (`Fanar-Aura-TTS-2`)

| Date | Observed | Spec says | Consequence / pinned by |
|---|---|---|---|
| 2026-04-25, re-confirmed 2026-08-29 | TTS works for the standard key (MP3 64 kbps 24 kHz mono / RIFF-WAVE PCM 16-bit 24 kHz; streamed chunks concatenate to a valid clip; `with_emotion` on `Radwa`); 0.8–2.4 s per call. | Code samples: "Text-to-Speech requires additional authorization and is not allowed by default." | Pinned by every `LiveAudioSpeechTest` case. |
| 2026-08-28, re-confirmed 2026-08-29 | **The budget is a sliding 24 h window**: `ratelimit-policy: 20;w=86400`, `x-ratelimit-limit: 20`, `remaining` = 20 − calls in the trailing 24 h, `reset` = seconds until the oldest of them ages out — not a calendar day. On 2026-08-29 the run's first call read `remaining: 10` / `reset: 39128` (nine calls from the previous evening still inside the window) and its eleventh read `remaining: 0`. The 2026-04-25 belief that the audio endpoints "rate-limit fast when called in quick succession" was this window seen without reading the policy header. | `Fanar-Aura-TTS-2` — 20 requests/day. | No more than 20 TTS calls in any trailing 24 h — [Live-suite budget](#live-suite-budget). |
| 2026-08-28 | **Exhausted window** (`x-ratelimit-remaining: 0`): the next call is **429**, envelope `code: "rate_limit_reached"` (not `exceeded_quota`), `retry-after: 28606` **equal to** `x-ratelimit-reset` (a countdown, ≈ 8 h), and **no `x-id`**. The first 429 ever captured on this key. | `retry-after` "counts down to a free slot" when your own quota ran out; `exceeded_quota` is documented but has never been observed. | Maps to `FanarRateLimitException` with `retryAfter()` ≈ `PT7H56M`; the hint exceeds the 30 s `maxDelay` ceiling, so `RetryInterceptor` surfaces it immediately (`fanar.retry_count=0`, ADR-025). Deliberately **not** asserted — provoking it burns the day's budget; recorded in `LiveRateLimitHeadersTest`'s javadoc. `LiveAudioSpeechTest` fails loudly on the second full run of a day: that is the budget, not the SDK. |
| — | `with_emotion=true` on a voice that is not emotion-capable (or on `Fanar-Sadiq-TTS-1`) answers 422. | `AvailableVoice.emotion_capable`; `with_emotion` description. | **Spec claim, unverified** — `LiveAudioSpeechTest.speech_withEmotionOnCapableVoice` exercises only the allowed combination. |

## Audio — transcriptions `POST /v1/audio/transcriptions` (`Fanar-Aura-STT-1`, `Fanar-Aura-STT-LF-1`)

| Date | Observed | Spec says | Consequence / pinned by |
|---|---|---|---|
| 2026-08-06, re-confirmed 2026-08-29 | STT works for the standard key (text / srt / json variants, server default text; 1.1–1.5 s per call). 2026-08-29: `Fanar-Aura-STT-1` counted 19 → 16 (`20;w=86400`), `Fanar-Aura-STT-LF-1` 9 → 6 (`10;w=86400`), both windows fresh (`reset: 86400`). 2026-08-06: synthesising a fresh TTS clip per STT test (8 extra TTS calls per run) exhausted the **TTS** window — those 429s were the speech budget, not a transcription limit. | Code samples: "Audio Transcriptions requires additional authorization and is not allowed by default." | `LiveAudioTranscriptionTest` synthesises **one** shared clip per JVM (`sourceClip()`) — keep it that way. Pinned by its four cases. |

## Audio — voices `GET` / `POST /v1/audio/voices`, `DELETE /v1/audio/voices/{name}`

| Date | Observed | Spec says | Consequence / pinned by |
|---|---|---|---|
| 2026-04-25 → 2026-08-29 | **History.** 2026-04-25: all three answered an authorization error. 2026-08-29: `GET` answers **200** with the built-in public voices (`Amelia`, `Harry`, `Noor`, `Jasim`, `Huda`, … — `type: public`, `emotion` flag), `x-id` present, no rate-limit headers; `POST` (multipart) answers **403**, envelope `code: "invalid_authorization"` / `"Invalid authorization"`, no headers, no `x-id`; `DELETE` is unobserved — the tests never get past `createVoice`. | 2026-08 spec: `POST` and `DELETE` "require additional authorization and are not allowed by default"; `GET` "always includes the built-in public voices". (The 2026-04 spec carried no note.) | `LiveAudioVoicesTest.listVoices` passes; `createVoice` and `deleteVoice` fail loudly at `createVoice` with `FanarAuthorizationException` — the intended diagnostic, 4 failing cases per run. |

## Images — `POST /v1/images/generations` (`Fanar-Oryx-IG-2`)

| Date | Observed | Spec says | Consequence / pinned by |
|---|---|---|---|
| 2026-04-25, re-confirmed 2026-08-29 | Generation works for the standard key: 1024 × 1024 PNG as base64, 4.4–4.7 s per call, `20;w=86400` (2026-08-29: `remaining` 13 → 10 — six calls from the previous evening still in the window). `revised_prompt` (2026-08 spec) is asserted by the test; the wire log's body cap hides it behind the base64. The model was listed in `/v1/models` on 2026-04-25 and still is. | 20 requests/day; no authorization note in the 2026-08 spec (the 2026-04 spec had one). | Pinned by `LiveImagesTest.generate_asyncCompletesAgainstLiveInfra` (no tolerance). The sync case still carries a 2026-04 lenient catch for authorization / not-found / timeout — it swallowed nothing on 2026-08-29 (4 × 200); its removal is pending a decision (0.4.0 Phase 7), not a behaviour. |

## Translations — `POST /v1/translations` (`Fanar-Shaheen-MT-1`)

| Date | Observed | Spec says | Consequence / pinned by |
|---|---|---|---|
| 2026-04-25, re-confirmed 2026-08-29 | Works for the standard key (en → ar, 0.7–1.0 s per call, `20;w=86400`; 2026-08-29: `remaining` 15 → 12). | 20 requests/day; no authorization note in the 2026-08 spec (the 2026-04 spec had one). | Pinned by `LiveTranslationsTest` (no tolerance). |

## Moderations and tokens — `POST /v1/moderations` (`Fanar-Guard-2`), `POST /v1/tokens`

| Date | Observed | Spec says | Consequence / pinned by |
|---|---|---|---|
| 2026-08-06, re-confirmed 2026-08-29 | `Fanar-Guard-2` is **callable while absent from `/v1/models`** ([Models](#models--get-v1models)); `50;w=60` headers present. Scores are continuous doubles whose range the spec does not pin (2026-08-29 probe: `safety` 4.72, `cultural_awareness` 4.99). | 50 requests/minute. | `LiveModerationsTest` asserts finiteness only. |
| 2026-08-29 | `/v1/tokens` answers with `x-id` but **no rate-limit headers**, although the request names a model (`Fanar-S-1-7B`: 6 tokens, `max_request_tokens: 4096`). | Not in the rate-limit table. | `LiveTokensTest` (no header assertion). |

Not covered live: `Fanar-Oryx-IVU-2` (image understanding — the code samples say gated; no live case) and
`Fanar-Sadiq-TTS-1` (Quranic TTS — absent from the listing; no live case).

## Transport level: rate-limit headers and `x-id`

Documented by the 2026-08-27 spec refresh (`info.description`); verified 2026-08-27 (curl), 2026-08-28
(`LiveRateLimitHeadersTest`, the TTS 429) and against the full 2026-08-29 run (100 exchanges). Header names arrive
lowercase.

| Header | Model 2xx (chat, TTS, STT, images, translations, moderations, Diwan) | Non-model 2xx (`/v1/models`, `/v1/tokens`, `GET /v1/audio/voices`) | Auth / gate 4xx (401, 403, the Sadiq-2 422) | Post-admission 4xx (Diwan 422) | TTS 429 (exhausted window, 2026-08-28) |
|---|---|---|---|---|---|
| `x-ratelimit-limit` | `50`, `20` or `10` per the model's policy | absent | absent | present | `20` |
| `x-ratelimit-remaining` | limit − requests in the trailing window; recovers one by one as they age out | absent | absent | present — the request counted | `0` |
| `x-ratelimit-reset` | seconds until the **oldest** counted request ages out: 60 right after a request into an empty window, then 59, 58 … — and it jumps *up* when the oldest request leaves (`Fanar-Sadiq`: 2 → 8) | absent | absent | present | `28607` → `28606` |
| `ratelimit-policy` | `50;w=60`, `20;w=86400` or `10;w=86400` | absent | absent | present | `20;w=86400` |
| `retry-after` | absent | absent | absent | absent | `28606`, equal to `x-ratelimit-reset` |
| `x-id` | present, equal to the body `id` | present | absent | absent | absent |

The windows are **sliding**, per requested model id: `remaining` is the limit minus the requests made in the last
`w` seconds, and `reset` is how long until the oldest of them drops out — so a 429's `retry-after` is the wait for
*one* slot, and a "20/day" model is really "20 in any trailing 24 h" (2026-08-29: the first TTS call of the run read
`remaining: 10` with `reset: 39128`, nine calls from the previous evening still inside the window). Rejections before
admission (401, 403, the gate's 422) do not count; a rejection after admission (Diwan's verse miss) does. What follows
for a header reader: never treat `reset` as a window boundary; tolerate every header being absent (non-model calls,
auth/gate errors and — per the spec — keys with unlimited quota); `exceeded_quota` has never been observed, so the
`rate_limit_reached` / `exceeded_quota` split in ADR-014 rests on the spec alone, as does the "upstream service
throttled" variant of `retry-after`. Typed exposure of these headers is ADR-026 (0.4.0 Phase 4); until then the
`Interceptor` SPI sees them raw — `LiveRateLimitHeadersTest` shows the pattern.

## Live-suite budget

The sliding 24 h windows, not per-minute pace, are what constrains the live suite. Counts are for one full
`FANAR_API_KEY=… ./mvnw -pl e2e -am verify`, **counted from the code and confirmed by the 2026-08-29 run's
`x-ratelimit-remaining` decrements** (each `@ParameterizedTest` runs once per codec — two codecs; the STT source clip
is synthesised once per JVM). The earlier "14 TTS calls per run" figure (2026-08-28) was an over-count.

| Model | Spec limit | Calls per full run | Where | Full runs per trailing 24 h |
|---|---|---|---|---|
| `Fanar-Aura-TTS-2` | 20/day | **11** | `LiveAudioSpeechTest` 5 cases × 2 codecs = 10, plus 1 shared clip in `LiveAudioTranscriptionTest.sourceClip()` | **1** — a second run inside the window 429s in the speech tests until enough calls age out |
| `Fanar-Aura-STT-LF-1` | 10/day | 4 | `LiveAudioTranscriptionTest` srt + json cases × 2 codecs | 2 |
| `Fanar-Aura-STT-1` | 20/day | 4 | `LiveAudioTranscriptionTest` text + default cases × 2 codecs | 5 |
| `Fanar-Oryx-IG-2` | 20/day | 4 | `LiveImagesTest` 2 cases × 2 codecs | 5 |
| `Fanar-Shaheen-MT-1` | 20/day | 4 | `LiveTranslationsTest` 2 cases × 2 codecs | 5 |
| `Fanar-Oryx-IVU-2`, `Fanar-Sadiq-TTS-1` | 20/day | 0 | no live case | — |
| chat models, `Fanar-Guard-2`, `Fanar-Diwan` | 50/min | ≈ 60 chat; 4 per other model (Diwan up to 3× on verse misses) | `LiveChatCompletionsTest`, `LiveModerationsTest`, `LivePoemsTest`, `LiveTokensTest`, `LiveModelsTest` | not a constraint for a sequential run |

Rules that follow:

- **No more than 20 TTS calls in any trailing 24 h** — one full run (11) fits only if fewer than nine other TTS
  calls happened in the previous 24 h. The 2026-08-29 run ended at `remaining: 0` because nine calls from the previous
  evening were still inside the window; the next full run fits from 2026-08-30 ≈ 10:34 UTC, once the run's own calls
  have aged out. Once the nightly CI run exists (0.4.0 Phase 7) it *is* the daily run, and it must start ≥ 24 h after
  the previous run's first TTS call. The `e2e-graalvm` native probe (`native` profile) adds one call per endpoint it
  touches, TTS and STT included.
- Budget-free runs are what the planned `live-audio` tag is for (`-Dgroups=live -DexcludedGroups=live-audio`,
  Phase 7); until it lands, run individual classes.
- Known-failing cases for the standard key, by design (6 per run, seen 2026-08-29): `LiveAudioVoicesTest.createVoice`
  and `.deleteVoice` (× 2 codecs — the `POST` 403) and `LiveChatCompletionsTest.conversation_sadiq2WithMadhab` (× 2 —
  the 422 gate). Anything else failing means something changed — and belongs in this ledger.

## Adding an observation

1. Capture the wire shape verbatim from the `fanar.wire` log (redact the key): status, envelope `code` and message,
   the headers that matter.
2. Add or amend the row with the date and the key class; keep superseded observations in the row as history.
3. Name the live test that pins it, and put the dated caveat in that test's javadoc in the same PR, under the
   tolerance rule in [CONTRIBUTING — Testing](CONTRIBUTING.md#testing) (fail loudly; retries only for a documented
   nondeterministic *semantic* outcome).
4. If an SDK behaviour claim changed with it, amend the ADR with a dated entry: this ledger records the server, the
   ADRs record the SDK.
