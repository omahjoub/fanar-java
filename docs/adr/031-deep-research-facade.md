# ADR-031 — Deep research: a minutes-long, quota-scarce, stream-first operation on `client.sadiq()`

- **Status**: Accepted
- **Date**: 2026-09-24
- **Deciders**: @omahjoub

## Context

The 2026-09-23 Fanar spec drop adds one operation, `POST /v1/sadiq/deep-research` (operations 13 → 14,
schemas 100 → 105, `info.version` unchanged; the YAML twin is semantically identical). It takes
`{model, input, depth, web_search, stream}` — `model` fixed to `Fanar-Sadiq-2`, `depth` one of
`quick` / `standard` / `comprehensive`, `stream` **defaulting to `true`** — and answers either with one
`DeepResearchReport` JSON object or, as a stream, with the chat SSE format: `ProgressChunk`s as research
passes complete, `TokenChunk`s carrying the draft, a new `ReportChunk` carrying the finished report,
then a `DoneChunk` whose `metadata` summarises the run. Every field of the report and its recursive
sections is nullable; `sources` items are untyped in the schema and `{source, citation_tag, quote,
was_cited}` in every example; `metadata` is a free-form object.

Three properties of the endpoint have no precedent in the SDK and shape this record:

1. **A run takes minutes.** The spec quotes 3–6 minutes for `quick`, 7–10 for `standard`, longer for
   `comprehensive`; its own curl sample allows 30. The client's `requestTimeout` defaults to 60 s.
   Until 2026-09-24 that timeout also ended any stream longer than itself on JDK 26 — the JDK's request
   timer changed what it covers — and the transport was corrected first, as its own change
   ([ADR-007](007-jdk-httpclient-default-transport.md)): `requestTimeout` now bounds the wait for
   response headers and nothing after them, on every JDK.
2. **A daily quota, consumed on admission.** The spec's rate-limit table gains
   `Fanar-Sadiq-2 (deep research)` at **20 requests/day** — keyed by endpoint, not by model, which is
   new: the same model is 50/min on chat and 200/min on validation. Its code samples state that "quota
   is consumed when the request is ADMITTED, so a retry spends another unit even if the first attempt
   produced nothing". The SDK's default `RetryPolicy` retries a 503, a 504 and any transport failure up
   to twice, and the policy is client-wide ([ADR-014](014-retry-policy-defaults.md)).
3. **The stream reuses chat's records but not chat's union.** The SSE format is the same, and four of
   the five event kinds are the chat records verbatim, yet chat never emits a report and deep research
   never emits a tool event. `StreamEvent` is sealed over exactly what a chat stream can emit, and two
   downstream adapters switch over it exhaustively (`FanarChatModel` in the Spring AI starter,
   `StreamAggregator` in `fanar-adk`). The spec's own SSE example also sends the first progress event
   with `"model": null`, which every chunk record rejected.

The endpoint is gated ("requires additional authorization and is not allowed by default" — a
`sadiq_deep_research` flag on the key). The gate itself was observed on 2026-09-24 — 403
`invalid_authorization`, as the spec declares, rejected before admission with no window headers —
but no run has been admitted, so every wire claim about the run below is the spec's and is recorded
as such in the [ledger](../WIRE_OBSERVATIONS.md#deep-research--post-v1sadiqdeep-research-fanar-sadiq-2).
ADR-002 settles that it is implemented anyway, and ADR-028 clause 4 settles how: gating shapes the live
test, never the implementation.

## Decision

1. **A second operation on `client.sadiq()`.** `SadiqClient` gains `deepResearchStream(DeepResearchRequest)`
   returning `Flow.Publisher<DeepResearchEvent>`, `deepResearch(DeepResearchRequest)` returning
   `DeepResearchReport`, and `deepResearchAsync(...)` (ADR-004). The domain — path prefix and OpenAPI tag
   both — is Sadiq, so the facade name ADR-028 asked to revisit once the `/v1/sadiq/*` family grew stays:
   a facade named for the family is exactly what the family's second member needs. The implementation
   lives in `SadiqClientImpl`, which now owns two endpoints.

2. **The request is a record, `model` is a `ChatModel`, `depth` is an open value class.**
   `DeepResearchRequest(model, input, depth, webSearch)` with `depth` and `webSearch` nullable so the
   server applies its defaults; `DeepResearchRequest.of(model, input)` for the common case. `model` reuses
   `ChatModel` under ADR-015's rule (the endpoint serves a chat model), as validation does.
   `DeepResearchDepth` is an open value-class record with `QUICK` / `STANDARD` / `COMPREHENSIVE`, `KNOWN`
   and a permissive `of(String)` (ADR-015), registered in both codecs' `WireValueModule`. The wire field
   `stream` is not modelled (ADR-023): the call-site method decides.

3. **Its own sealed union over the shared records: `DeepResearchEvent`.** In `qa.fanar.core.sadiq`,
   permitting `TokenChunk`, `ProgressChunk`, `DoneChunk` and `ErrorChunk` — the chat records themselves,
   each now implementing both unions — plus the new `ReportChunk(id, created, model, report)`.
   `StreamEvent` is untouched: it still lists exactly what a chat stream emits, no consumer `switch`
   breaks, and a consumer of the research stream switches over exactly what *that* stream emits. The
   SSE decoder becomes one class with a classifier per endpoint (`forChat`, `forDeepResearch`); the
   research classifier routes a top-level `report` right after `progress` and before the `usage` /
   `metadata` rule, and knows no tool shapes. This is the one place a sealed union's variants live in
   two packages — ADR-011's "same subpackage" convention is corrected in place to say so — because the
   wire really is shared and the types should say so rather than duplicate four records.
   `model` becomes nullable on every chunk record — the spec's example sends the first progress event
   without one, and an informational field must not fail a minutes-long run at decode — with
   `StreamEvent.model()` and `DeepResearchEvent.model()` documenting it, and `DoneChunk.metadata` —
   like the report's — is copied with a null-tolerant copy, since a run summary may carry null
   values. Two more rules of the research classifier: a first choice whose `finish_reason` is set to
   anything but `error` is the terminal `DoneChunk`, so the run's terminal frame is the terminal
   event even when it carries neither `usage` (often absent there, per the spec) nor `metadata` —
   chat keeps its rule, since its stop frames carry content and its terminal chunk carries `usage`
   — and, before any classifier runs, a frame whose top-level `error` is set is not an event but the
   server reporting a run it abandoned after admission (the shape the endpoint's own samples check,
   `"error" in chunk`): the decoder routes it like an HTTP error envelope, by code, then by the
   status the envelope names, else as an unexpected server failure carrying the raw frame, and
   throws the typed `FanarException` for the subscriber's `onError` (ADR-006). Chat's stream gets the
   same routing; before it, such a frame surfaced as a decode failure that lost the message. A
   codec's runtime failure during decoding is wrapped as `FanarTransportException`; a typed
   exception a codec throws passes through.

4. **One wire path: always the stream. The blocking variants collect from it.** Every variant sends
   `"stream":true` and `Accept: text/event-stream`; `deepResearch()` subscribes a collector that keeps
   the `ReportChunk`, ignores progress and draft events, and returns the report on the terminal signal.
   Three reasons. The stream is what the spec recommends. The server admits a streamed run at once and
   sends its headers, so the client's `requestTimeout` bounds the admission and never the run, at the
   default and on every JDK — whereas the JSON mode holds a silent connection for the whole run and
   can only work with a client-wide timeout of half an hour, raised for every other call too
   (ADR-027's worst case becomes `3 × 30 min`). And one path means one decoder, one seam test, one
   observation shape. The JSON mode (`stream:false`) is therefore not exposed; it is the same report
   with worse failure semantics, and exposing it is additive if a consumer ever asks.
   Failure semantics of the blocking variants — a received report is the result. Once the
   `ReportChunk` has arrived, `deepResearch()` returns its report whatever the stream does before or
   after it: a dropped connection, a bad terminal frame, an error event. The report is what the spec
   says to render and the terminal chunk carries only run metadata, so failing the call there would
   discard a unit of the day's quota the caller already has in hand. A transport failure after the
   report is recorded on the call's observation by the publisher; an error event after it is not
   recorded anywhere in this variant (the stream variant delivers both). Failures surface only when
   no report arrived: an in-stream error envelope as its typed exception, and an `ErrorChunk`, a
   stream that ends without a report, a transport or decode failure or an interrupt as
   `FanarTransportException` — the precedent `Streams` set — a runtime failure the stream produced
   is rethrown as it is and a checked one is wrapped. Interrupting the waiting thread cancels the
   subscription, which closes the response body and so the connection; cancelling the future from
   `deepResearchAsync` interrupts its worker and does the same. Whether the server refunds an
   abandoned admission is a ledger question.

5. **Never retried, whatever the client's policy.** `SadiqClientImpl` builds the research chain with
   `RetryPolicy.disabled()`: the retry interceptor still sits in the chain as the error boundary
   (ADR-012, ADR-025), so 4xx/5xx still become typed exceptions, but no attempt is ever repeated. A
   post-admission 503, 504 or transport failure would otherwise be retried up to twice and spend up to
   three of the day's twenty units on one call; a pre-admission failure is cheap for the caller to
   retry by hand on a minutes-long operation, and impossible for the SDK to tell apart from a
   post-admission one. Validation on the same facade keeps the client's policy. ADR-014's
   "client-wide" sentence is corrected in place to name this exception. An exhausted daily window
   (a 429 whose `Retry-After` is measured in hours) surfaces at once as `FanarRateLimitException`
   with `retryAfter()` and `rateLimit()` (ADR-025, ADR-026), which is the same behaviour the default
   policy would give — proved rather than assumed.

6. **Response records: nothing required, typed sources, one free-form map.** `DeepResearchReport`,
   `DeepResearchSection` (recursive `children`) and `DeepResearchSource` have nullable scalars — the spec
   declares no field required, and `{}` must decode — while lists and the map are never null.
   `sources` / `cited_sources` items are typed as `DeepResearchSource(source, citationTag, quote,
   wasCited)` from the spec's three consistent examples although the schema leaves them untyped: a typed
   record is the SDK's whole value, an item of another shape fails loudly at decode as a
   `FanarTransportException`, and correcting a record is a minor-version change before 1.0. The ledger
   carries the claim. `metadata` is `Map<String, Object>`, the `DoneChunk` precedent, and
   `COMPATIBILITY.md`'s "zero `Map<String, Object>`" line is corrected to the actual rule: typed
   everywhere except where the spec itself says `additionalProperties: true`.

7. **Observability and gating follow the house rules.** Operations are `fanar.sadiq.deep_research` and
   `fanar.sadiq.deep_research.stream` (ADR-013); the publisher owns the handle and records
   `fanar.stream.first_chunk_ms` / `fanar.stream.chunks`; `fanar.model` is the request's model. The seam
   test scripts the endpoint gate (403 `invalid_authorization`), the model gate (422 `unprocessable`)
   and the exhausted window (429), and asserts each routes by envelope code (ADR-006).
   `LiveDeepResearchTest` fails loudly until the key carries the flag; its caveat is written from the
   2026-09-24 observation, not a prediction.

8. **No framework-adapter work.** The Spring Boot 4 starter contributes one `FanarClient` bean, so the
   operation is reachable through it by construction (ADR-020). Spring AI has no model interface for
   research and ADR-024 forbids inventing one; an ADK `BaseTool` over it is "a separate record" per
   ADR-030's non-goals. Recorded in `COMPATIBILITY.md` §3.

## Alternatives considered

- **Add `ReportChunk` to `StreamEvent`.** *Rejected*: it makes every chat consumer handle an event chat
  never sends, breaks the two exhaustive switches in the reactor and any outside it, and puts a sadiq
  type in the chat union. ADR-005's "the hierarchy grows and every switch breaks" is the right model
  for a chunk chat itself gains; this is not that.
- **Five sadiq-only records.** *Rejected*: four of them would be byte-identical to the chat records on the
  wire, need their own flattening deserializers in both codecs, their own reachability entries and
  probes, and would leave `ProgressChunk`'s null-model bug in place for chat. Clause 3 pays for the shared
  records with one documented convention exception instead.
- **Expose the JSON mode, with a per-request timeout.** *Rejected* for now: ADR-007 keeps timeouts
  per client for v1, and the mode is strictly worse than the stream for the caller (clause 4). A
  per-request timeout is a separate, additive decision if a consumer needs it.
- **Keep the client's retry policy and document the cost.** *Rejected*: a caller who tries the endpoint
  once with defaults and a slow server could lose three of twenty daily units before seeing an error.
  The asymmetry decides it — a wrongly skipped retry costs one manual call, a wrong retry costs quota.
- **`List<Map<String, Object>>` for the source arrays.** *Rejected*: schema-faithful, but it gives up the
  one thing the SDK exists for and leaks a third map into the public API; the typed record's failure
  mode is loud and cheap to fix.
- **A dedicated `research()` facade or a `DeepResearchClient`.** *Rejected*: ADR-011 says facades mirror
  the API's domains; the domain is Sadiq and the tag ↔ facade rule of ADR-016 holds.
- **A job API (enqueue, poll).** *Rejected*: there is none on the wire — "a single long-lived HTTP call" —
  and the spec's own background-worker sample shows that lifecycle belongs to the application.

## Consequences

### Positive
- `POST /v1/sadiq/deep-research` is reachable with typed request, typed events and a typed report,
  closing the only gap between the 2026-09-23 spec and the SDK.
- Chat consumers are untouched: `StreamEvent` is unchanged and the Spring AI and ADK adapters compile
  as they are.
- The quota clause is enforced in code and proved through the public API, not left as advice.
- Routing was proved for three codes *before* the first live call, so the 2026-09-24 observation
  confirmed rather than surprised: the 403 arrived and surfaced as `FanarAuthorizationException`
  with `fanar.retry_count=0`, exactly as scripted.
- The one transport change the endpoint needed — `requestTimeout` as time-to-headers on every JDK —
  landed first as its own fix and also repaired chat and TTS streaming on JDK 26.

### Negative / Trade-offs
- `SadiqClientImpl` holds two dispatchers, and the "policy is client-wide" rule now has one exception
  a reader must know about. The exception is on the facade's Javadoc, in ADR-014 and here.
- The blocking variant discards progress events; a caller who wants them uses the stream. There is no
  "blocking with a progress callback" — that is the stream with a subscriber.
- Four shared records carry two `implements` clauses, and a sealed union's variants live in two
  packages, which ADR-011 must now explain.
- `DeepResearchSource` may be wrong: nobody has seen a live report. The failure is loud, and pre-1.0.
- On the research stream a terminal frame that also carried a last `delta.content` loses that delta
  to the `DoneChunk` routing; the report is the result, so nothing a caller should render is lost.
- The blocking variant's report-first rule means an error event that follows the report goes
  unrecorded there; a caller who needs it subscribes to the stream.
- The known-failing live set grows to 14 cases per run (`LiveDeepResearchTest`: 2 methods × 2 codecs),
  occasionally 15 with the Diwan verse-miss case, and a granted key would add 12–24 minutes to a full
  live run at `quick` depth — the ledger's budget table carries the derivation.

### Neutral
- Six reachability-metadata entries — the five new records and the `DeepResearchEvent` interface the
  codecs walk during introspection, as `StreamEvent` already has; the depth value class needs none,
  since both codecs map value classes through `WireValueModule` without reflection (see
  `reflect-config.json`) — and offline `e2e-graalvm` probes only: a live native probe would take
  minutes and spend a daily unit.
- `DeepResearchEvent` and `ReportChunk` need no codec registration; the records are plain and the
  classifier lives in core.
- The rate-limit prose in `ARCHITECTURE.md`, the ledger and ADR-028 that read "`Fanar-Sadiq-2` is
  50/min" is corrected: the table is now per endpoint.

## Proved by

- `FanarClientDeepResearchIntegrationTest.deepResearchStreamReachesTheEndpointAndDeliversEveryEventKind`
  (endpoint, bearer, `"stream":true` first in the body, depth and web-search on the wire, the spec's
  five event kinds decoded end to end with a null-model progress event),
  `.deepResearchCollectsTheReportFromTheSameStream`, `.deepResearchAsyncCrossesTheSameSeam`,
  `.deepResearchFailsWhenTheStreamReportsAnError`, `.deepResearchFailsWhenTheStreamEndsWithoutAReport`,
  `.theEndpointGateSurfacesAsAuthorizationException`, `.theModelGateSurfacesUnprocessableAsObservedOnChat`,
  `.anExhaustedDailyQuotaSurfacesAtOnceWithItsHint`,
  `.deepResearchIsNeverRetriedWhileValidateOnTheSameClientIs` (one hit for a 503 on deep research,
  a retry for the same 503 on validation, same client), `.cancellingTheAsyncRunReleasesTheConnection`
  — public builder → chain → JDK transport → scripted server.
- `FanarClientStreamsIntegrationTest.aBodySlowerThanTheRequestTimeoutIsStillDeliveredInFull` and
  `.headersSlowerThanTheRequestTimeoutFailTheCall` — the timeout definition clause 4 relies on (ADR-007).
- `AdapterParityTest.deepResearchRequestEncodesIdenticallyAcrossAdapters`,
  `.deepResearchRequestOmitsUnsetKnobsOnWire`, `.deepResearchReportDecodesIdenticallyAcrossAdapters`,
  `.deepResearchReportWithNothingSetDecodesToEmptyCollections`, `.reportChunkDecodesIdenticallyAcrossAdapters`,
  `.progressChunkWithoutAModelDecodesIdenticallyAcrossAdapters` — Jackson 2 and Jackson 3 agree,
  including the recursive sections, the typed sources and a null metadata value.
- `FanarClientDeepResearchIntegrationTest.anInStreamErrorEnvelopeFailsTheStreamWithTheTypedException`,
  `.anInStreamErrorEnvelopeFailsTheBlockingCallWithTheTypedException`,
  `.deepResearchReturnsTheReportWhenTheConnectionDropsAfterIt`, `.deepResearchReturnsTheReportWhenAnErrorEventFollowsIt`,
  `.aTerminalFrameWithoutMetadataOrUsageIsStillTheDoneChunk` — the in-stream error shape, the
  report-first rule and the terminal rule, through the public API.
- `DeepResearchWireIntegrationTest` (`e2e`, `@Tag("integration")`, offline) — the spec's SSE example,
  the in-stream error envelope and a bare terminal frame decoded end to end by the shipped Jackson 2
  and Jackson 3 adapters through `FanarClient.builder()` against the scripted server:
  `.theSpecExampleDecodesEndToEnd`, `.theBlockingVariantReturnsTheReport`, `.anInStreamErrorEnvelopeIsTyped`,
  `.aBareTerminalFrameIsTheDoneChunk`. Core's seam test decodes with a hand-rolled codec (core has no
  Jackson on its test classpath); this is where the classifier meets the real deserializers.
- `LiveDeepResearchTest.deepResearchStream_deliversProgressThenTheReport`, `.deepResearch_returnsTheReport`
  — against Fanar; gated, failing loudly until the key carries the flag.
- `Main.selfTest()` in `e2e-graalvm` — decode and encode probes for every new record under native image.
- Units: `SadiqClientImplTest` (the collector's every exit: report, report then a failure or an
  error event, no report, error chunk with and without detail, the typed in-stream error, rethrown
  runtime failure, rethrown `Error`, wrapped checked failure, interrupt, async cancellation, the
  never-retried chain, user interceptors on the research chain, observation names),
  `StreamEventDecoderTest` (the research classifier including the terminal rule, the in-stream
  error on both classifiers, codec failures wrapped and typed exceptions passed through, the chat
  classifier's unchanged fall-through), `ExceptionMapperTest` and `ErrorEnvelopeTest` (the
  header-less envelope routing and the envelope's `status`), `DefaultHttpTransportTest` (a response
  that lands as the wait expires is closed; runtime and `Error` causes rethrown raw),
  `SseStreamPublisherTest`,
  `DeepResearchEventTest`, `StreamEventTest` (nullable progress model, null-tolerant metadata), and
  the record tests in `qa.fanar.core.sadiq`.

## References

- ADR-002 Narrow core SDK scope (typed models for every Fanar endpoint)
- ADR-004 Sync-primary, async sugar (the three variants)
- ADR-005 Streaming via `Flow.Publisher` (the sealed-union contract this record keeps closed for chat)
- ADR-006 Unchecked exception hierarchy (gates routed by envelope code; stream failures as `FanarTransportException`)
- ADR-007 JDK `HttpClient` transport (`requestTimeout` = time to headers, defined 2026-09-24)
- ADR-011 Package conventions (the cross-package variants exception)
- ADR-012 / ADR-025 Interceptor chain and the retry boundary (the boundary stays; the attempts do not)
- ADR-013 Observability SPI (operation names, the publisher-owned handle)
- ADR-014 Retry policy defaults (client-wide, except here)
- ADR-015 Hand-written DTO conventions (value classes; `ChatModel` reuse; nullable fields)
- ADR-016 `FanarClient` builder and domain facades (new endpoints are new facade methods)
- ADR-017 SSE parsing (shape discrimination, now per endpoint)
- ADR-019 Pre-1.0 stability policy (additive; the nullable `model` is a minor-version change)
- ADR-020 / ADR-021 / ADR-024 / ADR-030 The framework adapters (why none changes)
- ADR-026 / ADR-027 Rate-limit visibility and the retry budget (the 429 shape; the worst-case arithmetic)
- ADR-028 Qur'an and hadith validation (the facade this operation joins; clause 4's gating rule)
- [COMPATIBILITY](../COMPATIBILITY.md) §1 capability table, §3 "what still belongs downstream"
- [WIRE_OBSERVATIONS](../WIRE_OBSERVATIONS.md) — the "Deep research" section: every wire claim, marked unverified
- Fanar OpenAPI `paths./v1/sadiq/deep-research`, 2026-09-23 drop — the normative contract
