# ADR-028 — Qur'an and hadith validation: a ninth domain facade, `client.sadiq()`

- **Status**: Accepted
- **Date**: 2026-09-15
- **Deciders**: @omahjoub

## Context

The 2026-09 Fanar spec refresh adds one operation, `POST /v1/sadiq/validate` (operations 12 → 13,
schemas 97 → 100, `info.version` unchanged). It takes `{model, text}` and returns `{id, text}`:
given arbitrary prose — an LLM answer, a user message, an article — it returns the same text with
verified Qur'anic verses replaced by the authenticated ayah, wrapped in
`<quran_start>` / `<quran_end>` and followed by a `[surah:ayah](quran.com)` markdown reference, and
verified hadith wrapped in `<hadith_start>` / `<hadith_end>` followed by a
`[collection:number](sunnah.com)` reference. **Quotations that cannot be verified come back plain
and untagged — that absence is the signal callers act on.**

Two things make it awkward to place. It is the first operation under a new `Sadiq` OpenAPI tag,
and through 0.4.0 every tag mapped 1:1 onto a `FanarClient` facade — eight tags, eight facades.
ADR-016 anticipated growth as "new Fanar endpoints become new facade methods, not changes to
`FanarClient`", which assumes the endpoint's domain already has a facade. This one has none.

And its only model is `Fanar-Sadiq-2`, which the endpoint's own description gates behind
"additional authorization … not allowed by default". The spec declares **403** for it, while the
same model's gate was observed answering **422** `unprocessable` / "Model not authorized" on chat
([WIRE_OBSERVATIONS](../WIRE_OBSERVATIONS.md), 2026-08-06). Which one this endpoint sends was
unobserved when this record was written, so the SDK encodes neither guess and routes purely by
envelope code. *(Resolved 2026-09-15: the endpoint answers **403** `invalid_authorization`. It is an
**endpoint-level** gate, a different mechanism from the model-level 422 — the endpoint check runs
first and short-circuits, so the two authorizations are independent. Both codes were already
scripted and routed; nothing in this decision changed.)*

ADR-002 settles whether to implement at all: typed models for **every** Fanar endpoint are in
scope, and "out of scope" names only framework-layer capabilities, never a declined endpoint.
What remains is an API-shape decision (ADR-019 window): where the operation hangs, what types its
`model` field uses, and how much of the returned markup the SDK interprets.

## Decision

1. **A ninth domain facade.** `SadiqClient`, in a new exported package `qa.fanar.core.sadiq`,
   reached via `FanarClient.sadiq()`, with `validate(SadiqValidationRequest)` and
   `validateAsync(...)` (ADR-004). The tag ↔ facade mapping of ADR-016 is the rule, not an artefact
   of the number eight; a ninth tag continues it. `qa.fanar.core.internal.sadiq.SadiqClientImpl`
   owns the endpoint, the wire format and the decoding, and shares the existing `Dispatcher` — no
   new plumbing (ADR-016).

2. **`model` is a `ChatModel`, not a new value class.** The spec's `SadiqValidationModels` schema
   lists only `Fanar-Sadiq-2`, already a `ChatModel` constant. The codebase's rule is not "every
   domain owns a model type" but "own one when the domain's models are not chat models":
   `PoemModel`, `ImageModel`, `TranslationModel`, `ModerationModel` and `TtsModel` each hold models
   absent from `ChatModel.KNOWN`, while `tokens` — whose endpoint serves chat models — deliberately
   takes a `ChatModel` and documents not narrowing it so callers can target a new model the day
   Fanar ships one. Validation serves a chat model, so it follows `tokens`. A dedicated type would
   also add a second `KNOWN` catalogue that no gating set covers.

3. **The response text is the wire string, verbatim. The SDK does not parse it.** Turning
   `<quran_start>` / `<hadith_start>` markup and markdown references into structured citations is
   post-processing — a framework concern under ADR-002 and `COMPATIBILITY.md` §3, not core's.
   `references[]` is typed because the server sends it as JSON; this arrives as one string, and a
   parser in core would be the first thing we own that Fanar can invalidate by changing a tag name.
   The same vocabulary also appears in the `X-Revised-Input` header on `POST /v1/audio/speech`; if a
   parser is ever written, one should serve both, and designing it from a single endpoint today
   would fix the wrong shape. Adding a parsed view later is additive; removing one is not.

4. **Gating shapes the live test, never the implementation.** The facade, its DTOs and its
   seam-crossing test ship whether or not our key is authorized — the house precedent set by voice
   cloning and `Fanar-Sadiq-2` on chat. The seam test scripts **both** gate codes Fanar is known to
   use — the endpoint's 403 `invalid_authorization` and the model gate's 422 `unprocessable` — and
   asserts each routes by envelope code (ADR-006), never by HTTP status or message text.
   `LiveSadiqValidateTest` fails loudly until the key is upgraded; its caveat is written from the
   2026-09-15 observation, not a prediction.

5. **No framework-adapter work.** The Spring Boot 4 starter contributes a single `FanarClient`
   bean (plus `FanarJsonCodec` and `RetryPolicy`), never one bean per domain, so `sadiq()` is
   reachable through it by construction — a new core domain needs no auto-configuration change, no
   property, and no per-facade bean or test (no starter test asserts a facade today; the only
   facade calls there are incidental to the retry seam test and the health indicator). The Spring AI
   starter gets **no adapter**: Spring AI has no model interface for quotation verification, and
   inventing one would be a Fanar-shaped API wearing a framework's name — the line ADR-024 draws.
   Consumers use `FanarClient.sadiq()` directly, as `FanarTranscriptionModel`'s javadoc already
   tells them to do for SRT transcription. Recorded in `COMPATIBILITY.md` §3 next to the
   `ModerationModel` gap, which is the same situation for the same reason.

## Alternatives considered

- **`validate()` as a method on `ChatClient`.** *Rejected*: different path prefix, unrelated DTO
  family, and it makes the chat facade a grab-bag. ADR-016 rejected the monolithic client because it
  "becomes unmaintainable as Fanar adds endpoints"; the `/v1/sadiq/` prefix suggests siblings are
  coming, and each would compound the mistake.
- **Defer until the key is authorized.** *Rejected*: contradicts ADR-002's every-endpoint promise,
  and leaves consumers with an authorized key unable to call a shipped endpoint. Gating has never
  blocked implementation here.
- **A dedicated `SadiqValidationModel` value class.** *Rejected*: see clause 2. Spec-faithful in
  form, inconsistent with the codebase's actual rule, and it buys no safety — ADR-015 makes these
  classes deliberately open, so it would not prevent an unsupported model reaching the server.
- **Naming the facade `validation()` or `quotations()`.** *Rejected*: ADR-011 says facades mirror
  the API's domains, and the domain — tag and path both — is Sadiq. Noted as the first facade named
  for a model family rather than a capability; revisit before 1.0 if Fanar's `/v1/sadiq/*` family
  grows beyond validation.
- **Parsing the tags into a sealed `Segment` union on the response.** *Rejected*: clause 3.
- **Surfacing `X-Revised-Input` in the same change.** *Rejected*: the spec's expanded description of
  that header is doc-only, and the SDK never exposed it — a pre-existing gap. Exposing it changes
  `speech()`'s return type or adds a sibling, and should follow ADR-026's principle that response
  metadata travels on observations and exceptions, not on DTOs. Its own decision, later.

## Consequences

### Positive
- `POST /v1/sadiq/validate` is reachable with typed request and response, closing the only gap
  between the 2026-09 spec and the SDK.
- Zero codec work: reusing `ChatModel` means neither `WireValueModule` changes, and the two flat
  records need no mixin — confirmed by `AdapterParityTest`, not assumed.
- Routing was proved for both plausible codes *before* the first live call, so the 2026-09-15
  observation confirmed rather than surprised: the 403 arrived and surfaced as
  `FanarAuthorizationException` with `fanar.retry_count=0`, exactly as scripted.
- Core gains no parser and no new runtime dependency; the module still has zero.

### Negative / Trade-offs
- A ninth public facade interface to name, document and keep stable — the cost ADR-016 flagged.
  "Eight facades" had been hard-coded in ADR-011, ADR-015, ADR-016, `ARCHITECTURE.md` and
  `PROJECT_STATE.md`, and every one of them had to be corrected. The count will drift again on the
  tenth domain, which is why those records now state the *rule* (one facade per OpenAPI tag) and
  point at `FanarClient` for the list.
- `validate()` accepts any `ChatModel`, so passing a non-validating model is a server-side 422
  rather than a compile error — the same trade `tokens` already makes.
- Callers who want structured citations must strip the markup themselves until a parser ships.
- The known-failing live set grows from 6 to **10** cases per run (`LiveSadiqValidateTest`:
  2 methods × 2 codecs), which the parked nightly job must exclude or be red every night.

### Neutral
- `SadiqValidationRequest` / `SadiqValidationResponse` are plain records, so they need two ordinary
  reachability-metadata entries (34 domain records now) and two `e2e-graalvm` probes.
- The endpoint is not rate-limit scarce: `Fanar-Sadiq-2` is 50/min, so the live cases cost nothing
  against the trailing-24 h audio windows.

## Proved by

- `FanarClientSadiqValidationIntegrationTest.validateReachesTheSadiqEndpointAndReturnsTheTaggedTextVerbatim`,
  `.quotationsThatCannotBeVerifiedComeBackUntagged`,
  `.theEndpointGateSurfacesAsAuthorizationException`,
  `.theModelGateSurfacesUnprocessableAsObservedOnChat`, `.validateAsyncCrossesTheSameSeam`,
  `.validateAsyncCompletesExceptionallyWithTheTypedException` — public builder → chain → scripted
  server: endpoint, bearer, model on the wire, text decoded unparsed, both gate codes routed.
- `AdapterParityTest.sadiqValidationRequestEncodesIdenticallyAcrossAdapters`,
  `.sadiqValidationResponseDecodesIdenticallyAcrossAdapters` — Jackson 2 and Jackson 3 agree on the
  wire shape, tags intact, with no codec registration.
- `LiveSadiqValidateTest.validate_tagsVerifiedQuotations`, `.validate_asyncCompletesAgainstLiveInfra`
  — against Fanar; gated, failing loudly until the key is upgraded.
- `Main.selfTest()` in `e2e-graalvm` — decode + encode probes prove the reachability metadata under
  native image.
- Units: `SadiqClientImplTest`, `SadiqValidationRequestTest`, `SadiqValidationResponseTest`,
  `FanarClientTest.sadiqReturnsNonNullSameInstance`.

## References

- ADR-002 Narrow core SDK scope (typed models for every Fanar endpoint; post-processing is downstream)
- ADR-004 Sync-primary, async sugar (the `validateAsync` variant)
- ADR-006 Unchecked exception hierarchy (gate routed by envelope code, never by message text)
- ADR-011 Package conventions (the ninth domain subpackage)
- ADR-015 Hand-written DTO conventions (nine functional domains; model value classes)
- ADR-016 FanarClient builder and domain facades (the 1:1 tag ↔ facade mapping is the rule)
- ADR-019 Pre-1.0 stability policy (additive; 0.5.0, never a patch)
- ADR-020 Spring Boot 4 starter shape (one `FanarClient` bean; no per-domain beans)
- ADR-021 Spring AI 2.0 adapter / ADR-024 Spring AI vendor options (why no validation adapter)
- ADR-026 Rate-limit telemetry (the precedent that response metadata stays off DTOs)
- [COMPATIBILITY](../COMPATIBILITY.md) §1 capability table, §3 "what still belongs downstream"
- [WIRE_OBSERVATIONS](../WIRE_OBSERVATIONS.md) — the `Fanar-Sadiq-2` gate (2026-08-06) and the
  Sadiq validation row
- Fanar OpenAPI `paths./v1/sadiq/validate`, 2026-09 refresh — the normative contract
