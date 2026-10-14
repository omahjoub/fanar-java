# ADR-032 — Deep research background jobs: start, follow, retrieve and cancel on `client.sadiq()`

- **Status**: Proposed
- **Date**: 2026-10-14
- **Deciders**: @omahjoub

## Context

The 2026-10 Fanar spec drop (operations 14 → 16, paths 13 → 15, schemas 105 → 119, `info.version` unchanged; the
YAML twin is semantically identical) adds background jobs to deep research:

- `POST /v1/sadiq/deep-research` takes `background: true` and answers at once with a `Job` — its id also in an `X-Id`
  header — whose research "no longer depends on your connection". With `stream: true` as well, the job's events
  stream on that same connection.
- `GET /v1/sadiq/deep-research/{job_id}` returns the job, or with `stream=true` its events as SSE, "each with an
  `id:`": "`data: [DONE]` ends the job; if a response ends without it, reconnect with `starting_after=<last id>` to
  resume". The samples say the server closes such a response "on purpose", send a keepalive "every 15 seconds of
  quiet", and add that "if a worker fails, the job restarts and its events replay from the start once". Jobs are kept
  24 h after they end; reading one "is not metered".
- `POST /v1/sadiq/deep-research/{job_id}/cancel` cancels asynchronously and idempotently; "Quota is not refunded".
- The endpoint description now calls background mode recommended: "Without `background`, one connection is held for
  the whole run … If you see timeouts or disconnects, switch to background mode." `stream`'s schema default is gone:
  "Defaults to `true`, or to `false` when `background` is set."

Three accepted records rest on premises this drop removes. [ADR-031](031-deep-research-facade.md) rejected "a job API
(enqueue, poll)" because "there is none on the wire", and chose the stream for its blocking variant partly because
the stream was what the spec recommended. [ADR-017](017-sse-parsing-strategy.md) declined `Last-Event-ID` reconnects
because "Fanar does not require it". [ADR-014](014-retry-policy-defaults.md) rejected automatic mid-stream retry
because the SDK "cannot know whether the caller's application state can tolerate duplicated tokens" — and a resume
after the last event id duplicates nothing the subscriber has seen. The drop also deleted two sentences the SDK
quoted: "quota is consumed when the request is ADMITTED" (ADR-031's never-retry rule) and "a single long-lived HTTP
call" (the facade's Javadoc). The quota rule now rests on the rate-limit table's 20/day for deep research and on
cancel's "Quota is not refunded".

Starting a run is gated for the SDK's key, synchronously or as a job — 403 `invalid_authorization`, observed for the
run on 2026-09-24 and for the job start on 2026-10-14 — while reading, cancelling and streaming an unknown job answer
404 for the same key (2026-10-14). No job has been created, so every wire claim about one below is the spec's,
recorded as `—` rows in the [ledger](../WIRE_OBSERVATIONS.md#deep-research--background-jobs). ADR-028 clause 4 settles
that gating shapes the live test, never the implementation.

## Decision

1. **Four operations join `client.sadiq()`; the synchronous three keep their behaviour.** `SadiqClient` gains
   `startDeepResearch(DeepResearchRequest)`, `retrieveDeepResearchJob(String)` and `cancelDeepResearchJob(String)`,
   each returning a `DeepResearchJob` and each with an `*Async` twin (ADR-004), and `deepResearchJobStream(String)`
   returning `Flow.Publisher<DeepResearchEvent>`. The domain is still Sadiq — path prefix and tag — so ADR-016's
   tag ↔ facade rule keeps them on the facade ADR-031 named. `deepResearch()`, `deepResearchAsync()` and
   `deepResearchStream()` stay on the synchronous stream: neither mode has been observed live, and re-basing an
   existing method on an unobserved mode moves the risk onto its current callers. **Trigger to revisit:** the first
   admitted live runs show a synchronous run lost to a timeout, a disconnect or a close without `[DONE]` — then the
   blocking variants are re-based on a job, with the same signatures and an interrupt that cancels the job.

2. **Every call fixes both wire flags; one wire path carries job events.** The spec made `stream`'s default depend on
   `background`, so the SDK relies on neither default: the synchronous variants send `"stream":true,"background":false`
   and `startDeepResearch` sends `"stream":false,"background":true`, spliced first into the encoded request — neither
   flag is a request-record component; the call-site method decides (ADR-023). The streamed background `POST`
   (events on the creating connection, the job id only in `X-Id`) is not exposed: job events have one wire path, the
   `GET`, and the never-retried call of clause 3 is then a request of seconds rather than one held for the run.

3. **The retry split follows the quota, not the endpoint.** `startDeepResearch` runs on the research chain that
   ADR-031 clause 5 builds with `RetryPolicy.disabled()`: a retried start would admit a second job and spend a second
   unit of the day's twenty. Retrieve, cancel and every request of a job stream run on the client's chain with its
   policy: reading a job is not metered, and cancelling is idempotent. The retry interceptor remains the error
   boundary on both chains (ADR-012, ADR-025), so an unknown or expired job surfaces as `FanarNotFoundException` and
   the gate as `FanarAuthorizationException`. A start whose body cannot be decoded names the `X-Id` header in its
   `FanarTransportException`: the job exists and is paid for, and no endpoint lists jobs. A start whose response never
   arrives — a header timeout, a dropped connection — can leave a paid job whose id the caller never learns; that is
   inherent to the wire, and the method's Javadoc says so.

4. **Records: the spec's `Job` minus its constants, the request echo reused.** `DeepResearchJob` carries the job's id,
   status, incomplete details, error, progress, attempt, four Unix-second timestamps, cancel source, request and
   result. The two constants, `object` (`"job"`) and `type` (`"deep_research"`), are not modelled: the record type and
   the path already fix both, and [ADR-015](015-dto-conventions.md) now states that rule. `id`, `status` and `request`
   are enforced, as the spec requires them; `createdAt`/`updatedAt` are `long`, `startedAt`/`completedAt` `Long`, and
   `progressPct`/`attempt` `int`, defaulting to 0 as the spec does. The echo decodes into `DeepResearchRequest` — the
   spec's `DeepResearchJobRequest` is the same four fields with the server's defaults filled in — so
   `startDeepResearch(job.request())` re-submits the same research. `JobError` and `IncompleteDetails` are records;
   `JobStatus`, `JobErrorCode`, `IncompleteReason` and `CancelSource` are open value classes (ADR-015).
   `JobStatus.isTerminal()` is true for every status but `queued` and `in_progress`, a status this SDK does not know
   included, so a polling loop ends and surfaces it instead of spinning until the job's retention runs out — the first
   behaviour method on a value class, noted in ADR-015. The record is `DeepResearchJob`, not the spec's `Job`, because
   its `request` and `result` are deep-research types; the job vocabulary keeps the spec's names and moves to a shared
   home if a second job type appears.

5. **The job stream resumes itself.** `deepResearchJobStream` opens the first response on the caller's thread —
   handshake failures throw from the call, as `deepResearchStream`'s do — and then delivers the job's events as one
   stream over as many responses as the server sends:
   - The SSE parser tracks the last event id by the WHATWG rules: `id:` fills a buffer that a blank line commits, so a
     connection lost in the middle of an event never moves the resume point. Each response's parser starts from the
     last committed id.
   - `data: [DONE]` completes the stream. A response that ends without it, cleanly or with a dropped connection, is
     followed at once by `GET …?stream=true&starting_after=<last id>` through the client's chain, which supplies the
     backoff and the typed errors.
   - Three consecutive responses that complete no SSE block — a keepalive is a block — fail the stream with
     `FanarTransportException`, so a server that keeps closing empty responses cannot spin it; three is the default
     policy's attempt count. Resuming without an event id after events were delivered fails too, rather than replay
     the job from its first event.
   - An in-stream `error` frame is held rather than raised: events that follow it supersede it — the spec restarts a
     failed worker and replays its events — and are recorded as the observation event `stream_error_superseded`;
     `[DONE]` or a failure while it is held raises it as its typed exception. The synchronous stream keeps ADR-031's
     immediate failure, because there the connection is the run.
   - Cancelling the subscription closes the response being read and interrupts a reconnect in flight. It does not
     cancel the job; `cancelDeepResearchJob` does.

   This is ADR-014's handshake-only retry applied per response: a resume continues after the last event the
   subscriber was given, so the SDK replays nothing; ADR-014 and ADR-017 are corrected in place to say so. Reconnects
   run the interceptor chain on the stream's virtual thread rather than the caller's, which ADR-012 now notes.

6. **Observability.** The operations are `fanar.sadiq.deep_research.start`, `.retrieve`, `.cancel` and
   `.retrieve.stream` (ADR-013's suffix on the wire operation it streams); `fanar.model` is recorded on the start, the
   only call that names a model. One observation spans a job stream's every response: chunks are counted across them,
   each reconnect adds a `stream_resumed` event, and the `http.*` attributes are the last request's. The two events
   join `retry_attempt` as the SDK's event vocabulary. `http.url` now carries job ids and event ids — an unbounded
   set — so `obs-micrometer` records it as high-cardinality by default, by ADR-026's rule for unbounded attributes;
   the operation name stays the low-cardinality key.

7. **Gating and live tests follow the house rules.** The seam test scripts the endpoint gate (403) and the gone job
   (404) on every operation and asserts each routes by envelope code (ADR-006). `LiveDeepResearchJobTest` runs one job
   at `quick` depth per codec — start, follow to `[DONE]`, retrieve, then cancel the finished job, which is returned
   unchanged at no cost — and one unknown-id case that only reads. The job case fails loudly until the key carries
   the flag; the unknown-id case passes for the standard key, whose job reads answer 404 rather than the gate's 403
   (first run, 2026-10-14).

8. **No framework-adapter work**, for ADR-031 clause 8's reasons: the Spring Boot starter's `FanarClient` bean reaches
   the new methods by construction, Spring AI has no model interface for research, and an ADK tool over a job would be
   a separate record.

## Alternatives considered

- **Re-base the blocking variants on a job now.** *Rejected for now*: both modes are unobserved; clause 1 holds the
  trigger.
- **Expose the streamed background `POST`.** *Rejected*: two wire paths for one event log, the job id only in a
  header ADR-026 keeps off DTOs, and a never-retried request held open for the whole run instead of for seconds.
- **A poll or await helper.** *Rejected*: `JobStatus.isTerminal()` reduces the loop to three lines, and how often to
  poll and how long to wait are the application's to decide (ADR-002); the job stream is the push alternative.
- **Expose event ids and a `startingAfter` parameter, leave resuming to the caller.** *Rejected for now*: every sample
  writes the same loop, and because the server closes responses on purpose, a stream that completed at such a close
  would end a job's events early with no signal. Exposing ids is additive (an overload and an event wrapper) if a
  consumer needs to resume in another process; until then a follower that restarts can replay from the first event,
  which costs no quota.
- **Fail a job stream on its first `error` frame, as the synchronous stream does.** *Rejected*: the spec restarts a
  failed worker and replays its events, so failing on the frame could report a job that then completes as failed —
  and a caller who reacts by starting another job spends a unit.
- **Name the record `Job`, model `object` and `type`, or decode the echo into a dedicated request record.**
  *Rejected*: clause 4.
- **`Last-Event-ID` instead of `starting_after`.** *Rejected*: the spec makes the query parameter primary
  ("`starting_after` takes precedence") and every sample uses it.
- **An idle-read watchdog now.** *Deferred*: the keepalives would let a stream detect a half-open connection — the
  samples read with a 120 s timeout — but the transport bounds only the wait for headers (ADR-007), chat streams share
  the gap, and reusing `requestTimeout` would make a caller with a short timeout reconnect through every quiet
  period. A stream-idle setting is its own decision.
- **A sub-facade (`sadiq().jobs()`) or a `DeepResearchClient`.** *Rejected*: ADR-016's tag ↔ facade rule, as in
  ADR-031.

## Consequences

### Positive
- Every operation in the 2026-10 spec is reachable with typed requests and responses, and proved through the public
  API before the first admitted call.
- The quota rule is enforced exactly where quota is spent; reads and cancels get the client's retry resilience.
- A job's events survive dropped connections and deliberate server closes without caller code.

### Negative / Trade-offs
- `SadiqClient` grows to twelve methods, with two notions of a run: the synchronous call and the job.
- A job stream's held `error` frame and the synchronous stream's immediate failure differ; each method documents its
  rule.
- A cancelled job-stream subscription leaves the job running; the method says so.
- Reconnects run user interceptors off the caller's thread, so thread-local context is absent there.
- `obs-micrometer` users who grouped metrics by `http.url` lose that tag; it stays on traces.
- A start whose response is lost can leave a paid job the caller cannot find.
- The deep-research live budget grows from 4 to 6 admissions per full run once the flag is granted, and the
  known-failing set grows by two while it is not — 16 per run, occasionally 17 (ledger).

### Neutral
- Three reachability-metadata entries, plus an `<init>` on `DeepResearchRequest`, which is now decoded as well as
  encoded; four value classes registered in both codecs' `WireValueModule`; no codec deserializer.
- The synchronous request body gains `"background":false` and still starts with `{"stream":true,`.

## Proved by

- `FanarClientDeepResearchJobIntegrationTest` — public builder → chain → JDK transport → scripted server:
  `startSendsBothFlagsFirstAndReturnsTheQueuedJob`, `startIsNeverRetriedWhileRetrieveAndCancelAre` (same client, 503s:
  1 + 2 + 2 hits), `retrieveGetsTheJobById`, `cancelPostsWithoutABody`, `theAsyncTwinsCrossTheSameSeam`,
  `anUnknownJobIsNotFoundOnEveryOperation`, `theEndpointGateSurfacesAsAuthorizationExceptionOnEveryOperation`,
  `aStartWhoseBodyCannotBeDecodedNamesTheJob`, `jobStreamDeliversTheEventsAndCompletesOnDone`,
  `jobStreamResumesAfterTheLastEventIdWhenAResponseEndsWithoutDone`, `jobStreamResumesAfterADroppedConnection`,
  `jobStreamReconnectsUnderTheClientRetryPolicy`, `jobStreamEndsWithNotFoundWhenTheJobIsGoneOnReconnect`,
  `jobStreamFailsAfterThreeEmptyResponses`, `keepaliveOnlyResponsesKeepTheJobStreamAlive`,
  `jobStreamWithoutEventIdsFailsRatherThanReplay`, `anErrorFrameFollowedByEventsDoesNotEndTheJobStream`,
  `anErrorFrameFollowedByDoneFailsTheJobStreamTyped`, `cancellingTheJobStreamReleasesTheConnection`,
  `oneObservationSpansEveryResponseOfAJobStream`.
- `FanarClientDeepResearchIntegrationTest.deepResearchStreamReachesTheEndpointAndDeliversEveryEventKind` — the
  synchronous body pins `"background":false`.
- `DeepResearchJobWireIntegrationTest` (`e2e`, `@Tag("integration")`, offline) — the spec's job examples and its
  `from_start` / `resumed` stream examples decoded end to end by both shipped Jackson adapters through
  `FanarClient.builder()`: `theSpecJobExamplesDecode`, `theSpecJobStreamDecodesAcrossAResume`,
  `anUnknownJobIsTyped`.
- `AdapterParityTest.deepResearchJobDecodesIdenticallyAcrossAdapters`,
  `.deepResearchJobWithOnlyItsRequiredFieldsDecodes`, `.deepResearchJobFailureShapesDecode`,
  `.deepResearchRequestDecodesFromTheJobEcho` — Jackson 2 and Jackson 3 agree.
- `MicrometerObservabilityPluginTest.httpUrlIsHighCardinalityByDefault`.
- `LiveDeepResearchJobTest.backgroundJob_followsToTheReportAndRetrievesIt`,
  `.unknownJob_isNotFoundOnEveryOperation` — against Fanar: the first gated, failing loudly until the key carries
  the flag; the second passing for the standard key (2026-10-14).
- `Main.selfTest()` in `e2e-graalvm` — a decode probe for the job records under native image.
- Units: `SseFrameAssemblerTest` (id tracking), `SseStreamPublisherResumeTest` (resume, `[DONE]`, the stall guard,
  the no-id rule, held error frames, cancellation in every phase, demand across responses, observation) beside the
  unchanged `SseStreamPublisherTest`,
  `StreamEventDecoderTest`, `StreamFlagTest`, `SadiqClientImplTest`, and the record tests in `qa.fanar.core.sadiq`.

## References

- ADR-002 Narrow core SDK scope (typed models for every endpoint; polling cadence is the application's)
- ADR-004 Sync-primary, async sugar (the `*Async` twins)
- ADR-005 Streaming via `Flow.Publisher` (the job stream reuses `DeepResearchEvent`)
- ADR-006 Unchecked exception hierarchy (404 and 403 routed by envelope code)
- ADR-007 JDK `HttpClient` transport (`requestTimeout` bounds each response's headers; no idle bound)
- ADR-012 / ADR-025 Interceptor chain and the retry boundary (interceptors on reconnects)
- ADR-013 / ADR-026 Observability and cardinality (operation names, events, `http.url`)
- ADR-014 Retry policy defaults (a resume is not a replay; the admission exception)
- ADR-015 DTO conventions (constants not modelled; `isTerminal()`)
- ADR-016 Builder and domain facades (the tag ↔ facade rule)
- ADR-017 SSE parsing (the last event id, honoured on job streams)
- ADR-019 Pre-1.0 stability (additive)
- ADR-023 Streaming TTS (call-site methods decide the wire flags)
- ADR-028 Sadiq validation (clause 4's gating rule)
- ADR-031 Deep research (the facade, the synchronous stream, the never-retried admission)
- [WIRE_OBSERVATIONS](../WIRE_OBSERVATIONS.md) — "Deep research — background jobs": every wire claim, unverified
- Fanar OpenAPI `paths./v1/sadiq/deep-research`, `/{job_id}`, `/{job_id}/cancel`, 2026-10 drop — the normative contract
