# Changelog

All notable changes to the Fanar Java SDK are recorded here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) with the
[pre-1.0 caveats from ADR-019](docs/adr/019-pre-10-stability-policy.md) — minor versions
may break public API until 1.0.0 ships.

## [Unreleased]

### Added

- **`fanar-adk`** — a Google ADK Java adapter: `qa.fanar.adk.FanarLlm` implements ADK's
  `BaseLlm` over a `FanarClient`, so ADK agents, the dev UI, sessions, callbacks and plugins run
  on Fanar models ([ADR-030](docs/adr/030-google-adk-adapter.md)). Layered directly on core, no
  Spring; the Jackson 2 codec ships at runtime scope because ADK already carries Jackson 2, so one
  dependency plus ADK is a working agent. The client can be supplied lazily (built on the first
  request) so a missing key or codec surfaces as a model error instead of vanishing into ADK's
  agent loader. Fanar-only knobs are per model instance (`FanarLlmOptions`); registry resolution
  by name (`fanar/<model>`) is an explicit `FanarLlm.register(...)`. Features Fanar cannot honour
  — tool declarations, including the transfer tool ADK injects for multi-agent trees, output
  schemas, unmapped parts — are refused before the wire by default (`UnsupportedFeaturePolicy.REJECT`,
  `UnsupportedFeatureException` carrying typed `UnsupportedFeature`s, deliberately not a
  `FanarException`) rather than dropped; `IGNORE` opts in. Streaming follows ADK's protocol
  (partials, then one aggregated final response), finish reasons map onto ADK's vocabulary, Sadiq
  references become grounding metadata, server-side tool calls are not emitted, and Fanar errors
  reach `onModelErrorCallback` unwrapped. No retry, logging or exception wrapping of its
  own; the OpenTelemetry span nests under ADK's `call_llm`. Provided-scope ADK 1.9.
- **`e2e`** — `LiveAgenticGateTest` pins the `Fanar-Agentic` model gate (422 "Model not
  authorized" for the standard key) and goes red the day it lifts, the signal to probe user tools
  ([ledger](docs/WIRE_OBSERVATIONS.md#chat-completions--post-v1chatcompletions)).

### Changed

- **BOM / release** — the published set is now ten library jars plus the BOM (`fanar-adk` added);
  `docs/RELEASING.md` counts and the consumer smoke follow.

## [0.6.0] - 2026-09-16

A reconciliation release. Every document in this repository was checked against the code rather
than against other documents, and the disagreements turned out to be defects more often than
drift: streaming never worked under GraalVM native-image, the Spring AI starter was published but
absent from the BOM in every release to date, the Spring Boot starter could not be placed on the
module path at all, an unmapped HTTP status surfaced as a fabricated `500`, and an observability
plugin could fail the call it was observing. All 29 ADRs now match the code and none carries an
amendment. Pre-1.0, so the observation-name and streaming-observation changes below break without
a deprecation cycle ([ADR-019](docs/adr/019-pre-10-stability-policy.md)); see **Changed** for
migration notes. Not yet on Maven Central — jars are attached to the GitHub Release
([ADR-029](docs/adr/029-publication-target.md)).

### Added

- **`fanar-core`** — `qa.fanar.core.Streams`, a blocking bridge from the streaming surface to a
  sync-shaped `Stream`: `Streams.toStream(Flow.Publisher<T>)`. Writing a `Flow.Subscriber` by hand
  to read a chat stream top to bottom was a lot of ceremony for a loop, and the SDK is sync-primary
  ([ADR-004](docs/adr/004-sync-primary-async-sugar.md)). It returns a `Stream` rather than an
  `Iterable` deliberately: a for-each has no close hook, so a `break` — or a `findFirst`, or a
  `limit` — would abandon the subscription and leak the HTTP response with no way to release it.
  `Stream` is `AutoCloseable`, so closing cancels. Generic over `Flow.Publisher<T>`, so streamed TTS
  gets it too ([ADR-023](docs/adr/023-streaming-tts-via-flow-publisher.md)). It pulls one item
  ahead, so the producer stays paced by the consumer instead of buffering the response, and a
  failure surfaces from the stream operation consuming it — unchecked exceptions untouched, checked
  ones wrapped in `FanarTransportException` so callers still only catch the sealed hierarchy
  ([ADR-005](docs/adr/005-streaming-via-flow-publisher.md), [ADR-006](docs/adr/006-unchecked-exception-hierarchy.md)).
- **`fanar-core`** — two exception leaves for a status the wire contract does not declare:
  `FanarUnexpectedClientException` (4xx) and `FanarUnexpectedServerException` (5xx and anything
  else). Both carry the status **as received** and a `null` `code()`, because inventing either is a
  lie a caller cannot detect. See *Changed* for what they replace.
- **`fanar-core`** — streaming observations now record the two attributes
  `FanarObservationAttributes` has always declared: `fanar.stream.first_chunk_ms` (measured at
  arrival, so it is the server's time-to-first-token and not the subscriber's back-pressure) and
  `fanar.stream.chunks`. Both were dead constants — no code path ever passed them to
  `attribute(...)` ([ADR-013](docs/adr/013-observability-spi.md)).
- **build** — `./mvnw -Ppublish package` attaches a `-sources.jar` and `-javadoc.jar` to every
  published module. Opt-in rather than part of every build: a full javadoc run is slower than the
  rest of the reactor combined and proves nothing a contributor needs proved per commit.
- **build** — reproducible builds. `project.build.outputTimestamp` is pinned, so the same commit
  produces byte-identical archives (verified: two builds, one SHA-256). Bump it to the release date
  when cutting a release.
- **build** — `Automatic-Module-Name` on both Spring starters, and `-parameters` globally. See
  *Fixed* for why the first is not cosmetic.
- **ci** — two documentation-and-consistency gates, both runnable locally and needing no JDK.
  `check-docs` replaces a Markdown-only link check: it now resolves **every** relative link (the
  wire-observations ledger points at the live test classes that pin each observation — those are the
  links whose breakage matters most, and the old rule never saw them), every `#fragment`, every
  `<img src>`, and the README's version snippets against the reactor version. `check-build` is new
  and guards three invariants a green build otherwise hides — the BOM managing exactly the published
  library modules, every class named as a *string* in `META-INF/services` or the GraalVM metadata
  resolving to a real source file, and no published module opting out of the coverage gate. The
  services check includes the descriptor's **filename**, which is itself the service-interface FQN:
  a rename reaches it through neither the compiler nor a find-and-replace over file contents, and
  missing it costs a consumer "no `FanarJsonCodec` on the classpath" at runtime.
- **docs** — [ADR-029](docs/adr/029-publication-target.md) (*Proposed*) records the publication
  decision and the constraint that shapes it: `groupId` is `qa.fanar`, reverse-DNS for `fanar.qa`,
  and Sonatype Central verifies a namespace by proving control of the domain — which is the Fanar
  team's, and unclaimed on Central as of 2026-09-16. The SDK therefore cannot publish its current
  coordinates on its own. The record decides everything decidable and leaves one question open.

### Changed

- **Breaking** — **observation names are normalised to `fanar.<domain>.<operation>`**, and a
  streaming variant now appends `.stream`. `fanar.chat` becomes **`fanar.chat.send`**; streaming
  stops reusing it and gets **`fanar.chat.stream`**; streamed TTS gets
  **`fanar.audio.speech.stream`**, separate from one-shot `fanar.audio.speech`. The two have
  different latency profiles and conflating them makes both unreadable. Dashboards and alerts keyed
  on `fanar.chat` need updating. This is the last release in which such a rename is free
  ([ADR-013](docs/adr/013-observability-spi.md), [ADR-019](docs/adr/019-pre-10-stability-policy.md)).
- **Breaking** — **a streaming observation now spans the stream instead of closing at the
  handshake.** The publisher takes ownership of the handle and closes it on the terminal signal —
  completion, failure or cancellation alike. The visible consequence: a stream that dies mid-flight
  is now reported as a failure, where before it was telemetered as the clean success its handshake
  had been. An adapter that assumed `close()` arrives before the first chunk will see it arrive
  after the last one ([ADR-013](docs/adr/013-observability-spi.md)).
- **Breaking** — **a status the wire contract does not declare no longer becomes a server error.**
  Previously every unmapped status mapped to `FanarInternalServerException`, which meant a 4xx from
  an intermediary — a proxy answering `407`, a gateway answering `405` — was retried three times
  with backoff and surfaced to the caller as a *server* fault reporting `httpStatus() == 500` and
  `code() == INTERNAL_SERVER_ERROR`, neither of which the server had sent. Unmapped statuses now map
  to the two new `Unexpected*` leaves, filed by HTTP class, carrying the real status and a `null`
  code. The branch decides retryability, so an unmodelled 4xx is no longer retried — with `408` and
  `425` the documented exception, client-class by number but meaning "try again"
  ([ADR-006](docs/adr/006-unchecked-exception-hierarchy.md), [ADR-014](docs/adr/014-retry-policy-defaults.md)).
- **Breaking** — **an `ObservabilityPlugin` can no longer fail a call.** The client guards whatever
  plugin it is given, so a `RuntimeException` from any SPI method is absorbed and the request
  proceeds; a plugin that throws from `start`, or returns `null`, still yields a usable handle.
  `Error` is not caught. Failures are not silent: the first from a given plugin is reported at
  `WARNING` through `System.Logger` — JDK-built-in, so core keeps its zero dependencies — and later
  ones drop to `DEBUG` so a plugin throwing on every call cannot bury the first report.
  This was previously true only of *two or more composed* plugins, because
  `FanarClient.Builder.observability(...)` never routed through `compose(...)` and `compose(single)`
  unwraps — so composing more plugins made you safer, which is backwards. The plugins users install
  are adapters over networked backends, and "must not throw" is not a promise an exporter queue or a
  meter registry can keep ([ADR-013](docs/adr/013-observability-spi.md),
  [ADR-022](docs/adr/022-observability-compose-factory.md)).
- **Breaking** — `ObservabilityPlugin.compose(...)` now contains a child's failure instead of
  propagating it and short-circuiting the remaining plugins. One broken backend previously both
  failed the request *and* blinded every plugin registered after it. A `null` child is still
  rejected at construction: a null is a wiring mistake that will never become correct, while a
  throwing plugin is a runtime condition ([ADR-022](docs/adr/022-observability-compose-factory.md)).
- **`fanar-java-bom`** — now manages `fanar-spring-ai-starter`. See *Fixed*.

### Fixed

- **`fanar-core`** — **streaming was unusable in a GraalVM native image.** Every `StreamEvent` the
  server can send is a record Jackson introspects, and none of them were in the shipped reachability
  metadata, so the first chunk of any `chat().stream()` call threw
  `UnsupportedFeatureError: Record components not available for record class
  qa.fanar.core.chat.TokenChunk`. Eight entries were missing — `TokenChunk`, `DoneChunk`,
  `ErrorChunk`, `ToolCallChunk`, `ToolResultChunk`, the `StreamEvent` interface, and the nested
  `ProgressMessage` / `ToolResultData`. The native self-test never caught it because it decoded ten
  domain responses and no stream chunk; it now decodes all six `StreamEvent` leaves
  ([ADR-005](docs/adr/005-streaming-via-flow-publisher.md), [ADR-009](docs/adr/009-native-image-day-one.md)).
- **`fanar-json-jackson2` / `fanar-json-jackson3`** — their reachability metadata was in the unified
  `reachability-metadata.json` schema, which **GraalVM for JDK 21 does not read** — so on the SDK's
  own floor, and in CI, those files were inert. Both are now `reflect-config.json`, the one schema
  every supported toolchain reads. Measured on GraalVM 21.0.11 and 25.0.4 with a class registered in
  each schema: legacy resolved on both, unified resolved only on 25. The rule is now written down —
  the schema tracks the supported Java floor, not the newest toolchain
  ([`GRAALVM.md`](docs/GRAALVM.md), [`COMPATIBILITY.md`](docs/COMPATIBILITY.md)).
- **`fanar-java-bom`** — **`fanar-spring-ai-starter` shipped but was absent from the BOM**, in
  every release to date: the jar has been attached since v0.1.0 and the module deploy-enabled since
  v0.2.0, and no release from v0.1.0 to v0.5.0 managed it. A consumer following the README —
  importing the BOM and declaring the artifact without a version, which is the BOM's entire purpose
  — got a resolution failure. One missing entry, five releases, and nothing in the build that could
  have caught it; the new `check-build` gate now compares the BOM's managed set against the
  published set in both directions ([ADR-010](docs/adr/010-module-layout.md)).
- **`fanar-spring-boot-4-starter`** — **the jar could not be placed on the JPMS module path at
  all.** With no `module-info.java` (deliberate, [ADR-020](docs/adr/020-spring-boot-4-starter.md))
  JPMS derives a module name from the *filename*, and `fanar-spring-boot-4-starter` derives the
  component `4`, which is not a Java identifier: `jar --describe-module` reports
  `Invalid module name`. An explicit `Automatic-Module-Name: qa.fanar.spring.boot.v4` restores the
  fallback the ADR assumed. `fanar-spring-ai-starter` gains `qa.fanar.spring.ai` for the same
  reason — it derived a usable name, but not one matching its package root
  ([ADR-011](docs/adr/011-package-conventions.md)).
- **`fanar-core`** (internal) — the exception message for an unmapped status no longer doubles
  (`HTTP 418: HTTP 418`); the status travels on `httpStatus()` and is not re-prefixed onto a
  message that already carries it.
- **`fanar-core`** — `package-info.java` named a package that does not exist (`moderation`; it is
  `moderations`), and `module-info.java`'s own javadoc described the public API as the top-level
  package plus `spi` while the descriptor beneath it exported nine more.
- **build** — removed the `spring-milestones` repository. It resolved nothing — both `<releases>`
  and `<snapshots>` were disabled — while its comment claimed Spring AI was still in milestone phase
  and the repository was scoped to admit it. Spring AI 2.0.1 is GA on Central. Proved harmless by a
  fully offline `./mvnw -o clean verify`.
- **ci** — the GraalVM native-image job now runs a **matrix on GraalVM for JDK 21 and 25** (21 is
  the floor and the leg that blocks a release; 25 is forward-coverage and surfaces deprecations
  first), and is roughly twice as fast per leg: quick build mode (`-Ob`) cuts the compile phase from
  19.2s to 5.6s while leaving the *analysis* phase — the only thing this gate asserts — unchanged,
  and `-DskipTests` stops re-running a test suite the `test` job already runs on both JDKs. The legs
  run in parallel, so wall-clock does not double.
- **ci** — the release build and CI now invoke `./mvnw`, the wrapper pinned to Maven 3.9.16, rather
  than whatever `mvn` the runner image happens to carry; and `-DskipITs=false` is gone from the
  release build, where it did nothing (there is no Failsafe in this project) while implying an
  integration-test split that does not exist.

### Docs

- **Every ADR now reflects the code, and none carries an amendment.**
  [ADR-019](docs/adr/019-pre-10-stability-policy.md) states the rule: until 1.0.0 an ADR states the
  current decision as if decided today, because pre-1.0 there is no commitment to deviate from — an
  amendment documents a promise that was never made, and costs every reader a reconciliation across
  several statements. Sixteen amendment sections across nine records were folded into their bodies,
  keeping the reasoning: a discovery that *explains* a decision belongs in Context or Consequences,
  not in a dated changelog entry. Eight records dated 2026-04-23 described the SDK as planned rather
  than as built and were reconciled in full.
- **`docs/API_SKETCH.md`** — eleven snippets did not compile, including a helper class that never
  existed. The document claimed every snippet matched a shipped type.
- **`docs/JAVA_LIBRARY_BEST_PRACTICES.md`** — its "Central-grade expectations" section asserted five
  things in the present tense that were not implemented, under a heading telling the reader to treat
  violations as bugs. It is now split into *In force* and *Prerequisites for Maven Central — not yet
  implemented*. Several of the rules were themselves wrong: the artifact↔package correspondence rule
  did not match any module, "no shading anywhere" was contradicted by the native-image probe, and
  the public-API definition omitted the nine exported domain packages.
- **module diagram** — `docs/images/fanar_java_module_dependencies.svg` said *8 domain facades*
  since the ninth shipped in 0.5.0. It is embedded in both the README and `ARCHITECTURE.md`, so it
  was the most-seen wrong statement in the repository, and no link checker would ever have read it.
- **`docs/WIRE_OBSERVATIONS.md`** — the known-failing count corrected to 11, a duplicated row
  removed, an SDK type name removed from the "Spec says" column, and the 34-field chat schema
  attributed to the 2026-08 refresh rather than 2026-09 (verified by diffing four spec revisions).
- **`docs/CONTRIBUTING.md`** — the reading order now includes the wire-observations ledger, which
  the Testing section already assumed you had read; `-Dgroups=integration` no longer recommends a
  command that fails the coverage gate; and the no-`Thread.sleep` rule says what it means, after
  being violated seventeen times. Thirteen of those were replaced with latches.

## [0.5.0] - 2026-09-15

Full coverage of the published Fanar surface. The 2026-09 spec refresh is absorbed — a ninth
domain facade, `client.sadiq().validate(...)`, verifies the Qur'anic verses and hadith quoted in
arbitrary text — alongside a fix for an exception accessor that could never return a value, and a
documentation correction: the chat endpoint does not *reject* user `tools`, it accepts and silently
ignores them, which is a materially different thing for a caller to handle. Pre-1.0
([ADR-019](docs/adr/019-pre-10-stability-policy.md)): **no breaking changes** this time. Not yet on
Maven Central — install via `./mvnw install` from a clone, or download the artifacts attached to
this release.

### Added

- **`fanar-core`** — a ninth domain facade, `FanarClient.sadiq()`, over the new
  `POST /v1/sadiq/validate` endpoint: `SadiqClient.validate(...)` / `.validateAsync(...)` with the
  records `SadiqValidationRequest` (`model` + `text`) and `SadiqValidationResponse` (`id` + `text`)
  in the new exported package `qa.fanar.core.sadiq`. Given arbitrary prose it returns the text with
  verified Qur'anic verses replaced by the authenticated ayah, wrapped in
  `<quran_start>` / `<quran_end>` and cited to quran.com, and verified hadith wrapped in
  `<hadith_start>` / `<hadith_end>` and cited to sunnah.com; **quotations it cannot confirm come back
  plain and untagged**. `text()` is the wire string verbatim — the SDK does not parse the markup,
  which stays a downstream concern ([ADR-028](docs/adr/028-sadiq-validation-facade.md), ADR-002).
  `model` is a `ChatModel` rather than a new value class, following `TokenizationRequest`: the
  endpoint's only model, `Fanar-Sadiq-2`, is already a chat model. The endpoint requires additional
  authorization, so its live cases fail loudly until the key is upgraded — observed 2026-09-15:
  HTTP **403** `invalid_authorization`. That is an *endpoint-level* gate, distinct from the
  *model-level* 422 `unprocessable` the same `Fanar-Sadiq-2` answers on chat; the endpoint check
  short-circuits, so the two authorizations are independent. Both codes are proved routed by
  envelope code against a scripted server.
- **Spec** — `api-spec/openapi.json` and its YAML twin refreshed to the 2026-09 Fanar spec:
  **12 → 13 operations, 97 → 100 schemas** (paths 11 → 12), `info.version` still 1.0.0. Additive
  only — no path, schema or field removed or changed. Besides the new endpoint: the `X-Revised-Input`
  response-header description on `POST /v1/audio/speech` now covers hadith detection and states that
  hadith tags and the quran.com / sunnah.com links are stripped before synthesis (the SDK does not
  surface that header today — see [PROJECT_STATE](docs/PROJECT_STATE.md)), and `info.termsOfService`
  moved to `https://api.fanar.qa/terms-of-service`.

### Changed

- **`fanar-spring-boot-4-starter` / `fanar-spring-ai-starter`** — no change required, by design.
  The SB4 starter contributes a single `FanarClient` bean rather than one per domain, so
  `sadiq()` is reachable through it with no extra configuration. Spring AI gets no adapter: it
  has no model interface for quotation verification, so consumers call `FanarClient.sadiq()`
  directly — recorded in `COMPATIBILITY.md` §3 beside the `ModerationModel` gap.
- **docs** — ADR-011, ADR-015 and ADR-016 amended (dated) for the ninth domain: facades map 1:1 to
  domains, so a new OpenAPI tag gets a new facade rather than a method on `chat()`. The
  wire-observations ledger gains a `Sadiq validation` section — every row marked **spec claim,
  unverified** until the endpoint can be called — and the known-failing live set grows from 6 to 10
  cases per run.
- **docs** — a corrected scope claim. PROJECT_STATE said Fanar's chat endpoint *rejects* user
  `tools` / `tool_choice`; a 2026-09-15 live probe — the first time either was ever put on the wire,
  the claim until then being a reading of the schema — shows it **accepts and silently ignores**
  them: HTTP 200, `tool_calls` empty, the tool name absent from the body, and `prompt_tokens`
  identical with and without the array, so the field is discarded before the model sees it.
  No SDK behaviour changes and user tool calling stays out of scope, but the reason is now that a
  caller gets **no** signal — which is what makes the silent degradation of Spring AI tool
  callbacks (ADR-021, ADR-024) the correct description. The same probe settled the three
  undocumented chat model ids found earlier that day: `Fanar-Agentic` and `Fanar-Sadiq-Agentic` are
  gated for the standard key (422 "Model not authorized") and `Islamic-RAG` is an alias for
  `Fanar-Sadiq`, so **no `ChatModel` constants are shipped for any of them** and the item moves from
  Planned to Deferred. It also found that `Fanar` routes **by query**: a live-data prompt answers
  `"model": "web_search"` with web `references`, an ordinary one `Fanar-C-2-27B` — so the
  response `model` need not name a model at all, and `references` are not Sadiq-only.
  [Ledger](docs/WIRE_OBSERVATIONS.md#chat-completions--post-v1chatcompletions).
- **docs** — six broken intra-document anchors fixed (two in the wire-observations ledger, four in
  `GRAALVM.md`). `check-docs` validates that relative `.md` *files* resolve; it checks no anchors, so
  these had accumulated silently. Every Markdown file in the repository now resolves every `#fragment` it links to.

### Fixed

- **`fanar-core`** — `FanarContentFilterException.filterType()` was **dead public API**: it could
  never be non-`null` for any exception the SDK produced. The error-envelope parser read only
  `code` and `message`, so the spec's `type` member was discarded before the exception was built,
  and both sites that construct the exception used the 1-arg constructor. The public
  `ContentFilterType` constants had no code path that could produce them, while the Javadoc and
  [ADR-006](docs/adr/006-unchecked-exception-hierarchy.md)'s own example read
  `showRefusalUi(e.filterType())`. The envelope's `type` now reaches `filterType()` on both routes
  — the typed `content_filter` code and the HTTP-400 fallback — mapped permissively per
  [ADR-015](docs/adr/015-dto-conventions.md), with absent / JSON-`null` / blank all yielding
  `null`. Additive under [ADR-019](docs/adr/019-pre-10-stability-policy.md); no signature changed.

  Note that a live probe on 2026-09-15 found Fanar's moderation refuses **inside a 200**, in
  ordinary assistant text — no `content_filter` error, no `FinishReason.CONTENT_FILTER`, no
  `RefusalPart` — and that `type` is `null` on every error envelope captured so far. Handle refusals
  in the response body; this exception and a populated `filterType()` are a bonus, not a contract.
  See the [wire-observations ledger](docs/WIRE_OBSERVATIONS.md#error-envelope-shape). The fix makes
  the accessor honest, not the server more forthcoming.

- **`fanar-core`** (internal) — the envelope parser tolerates a non-string value in any member,
  reading it as absent instead of failing the parse. `param` and `type` are spec-nullable and were
  both observed `null` on the wire; reading them with the plain string reader threw, which discarded
  the whole envelope and silently dropped the response to HTTP-status routing. Only `code` is
  load-bearing, and a non-string `code` still yields no envelope, so status routing takes over as
  before; strictness about JSON *syntax* is unchanged. `param` is now parsed but not surfaced on the
  public API — see PROJECT_STATE for the deferred decision.

## [0.4.0] - 2026-08-30

"Proof over coverage": every behaviour an ADR promises to a consumer is now proved through the
public API by a named seam-crossing test, what the live Fanar API actually does is recorded in a
dated ledger, the wire log no longer loses failures, and the retry boundary both reports the
server's rate-limit window and stops sleeping past a total budget. Pre-1.0
([ADR-019](docs/adr/019-pre-10-stability-policy.md)): one breaking change, marked below. Not yet on
Maven Central — install via `./mvnw install` from a clone, or download the artifacts attached to
this release.

### Changed

- **Breaking** — `RetryPolicy`'s canonical constructor gains a fourth `Duration`, `maxTotalDelay`,
  after `maxDelay` (seven components, was six). Migration: prefer the new `RetryPolicy.builder()`
  (starts from `defaults()`, one setter per knob), or insert `Duration.ofMinutes(1)` — the default —
  as the fourth positional argument. Deconstruction patterns over the record gain a component too.
  Pre-1.0 per [ADR-019](docs/adr/019-pre-10-stability-policy.md); see
  [ADR-027](docs/adr/027-retry-policy-builder-and-budget.md).
- **`fanar-core`** (internal, no behaviour change) — the eight domain facades no longer each assemble the
  interceptor chain, record the transport attributes and call the transport: that plumbing lives in one
  `internal.dispatch.Dispatcher` (ADR-018 — internals are not a contract). `http.url` is now read from the
  request the facade built rather than the facade's endpoint field; the two were always the same URI.

### Added

- **`fanar-core`** — a total sleep budget for retries ([ADR-027](docs/adr/027-retry-policy-builder-and-budget.md)):
  `RetryPolicy.maxTotalDelay()` (default 1 min — the worst case the other defaults already allowed, so
  nothing changes at the defaults) bounds the sum of all sleeps within one call; a sleep that would exceed
  it — computed back-off or honoured `Retry-After` hint — is never started, retrying ends and the exception
  surfaces with the hint preserved, like the ADR-025 ceiling. Plus `RetryPolicy.builder()` and
  `withMaxTotalDelay(...)`.
- **`fanar-spring-boot-4-starter`** — `fanar.retry.max-total-delay` (default `1m`); the `RetryPolicy` bean
  is built through the builder, so a `max-delay` raised above the budget fails the context at startup
  ([ADR-020](docs/adr/020-spring-boot-4-starter.md), amended).
- **`fanar-core`** — rate-limit visibility ([ADR-026](docs/adr/026-rate-limit-telemetry.md)): the retry boundary
  publishes `fanar.ratelimit.limit` / `.remaining` / `.reset` / `.policy` observation attributes from every response
  that carries Fanar's rate-limit headers (successes and 429s alike; the last attempt's values win), and both HTTP
  429 subtypes gain `rateLimit()` — a `RateLimitInfo` record (`limit`, `remaining`, `reset`, raw `policy`, derived
  `window()`), `null` when the server sent no headers. `reset` is the wait until one slot frees: Fanar's windows
  slide, so it is never a boundary. Additive — new constants, a new record, new constructor overloads.
- **`fanar-obs-micrometer`** — the unbounded `fanar.ratelimit.remaining` / `.reset` are recorded as
  high-cardinality key-values so they never become metric tags;
  `MicrometerObservabilityPlugin.builder(...).highCardinalityKeys(Predicate)` replaces the rule.
- **Seam-crossing integration tests** (`*IntegrationTest`, `@Tag("integration")`) proving, through the public
  API against a scripted local server, what the ADRs promise: retry on 5xx, the `Retry-After` ceiling,
  handshake-only retry for chat and TTS streams (a mid-stream drop is `onError`, never re-requested),
  `sendAsync` through the chain on a virtual thread — and the same seam through every consumer surface: the
  Spring Boot starter's `fanar.retry.*` knobs and `RetryPolicy` bean, the Spring AI `FanarChatModel`, the
  SLF4J / OpenTelemetry / Micrometer adapters (retry telemetry, W3C context on every attempt) and the
  wire-logging interceptor (raw 503 visible below the retry boundary). Backed by a new unpublished
  `test-support` fixture module (`ScriptedHttpServer`, `CollectingSubscriber`) and a 60 s JUnit timeout
  backstop on every test. `CONTRIBUTING.md` gains a "Testing" section with the rule: an ADR's
  consumer-observable promise is proved by a named `*IntegrationTest`.
- **docs** — [`docs/WIRE_OBSERVATIONS.md`](docs/WIRE_OBSERVATIONS.md): a dated, per-endpoint ledger of what the
  live Fanar API does where it differs from the spec — model gating answers 422, `/v1/models` is visibility-scoped,
  `stop` is ignored, no user `tools`, the response `model` names the routed backend, Diwan's nondeterministic verse
  miss, voice-personalization gating (`POST` 403, `GET` works), the rate-limit header shapes per response class and
  the fact that the windows are sliding (`x-ratelimit-reset` counts down to the oldest request ageing out; "20/day"
  means 20 in any trailing 24 h) — each row naming the live test that pins it, plus the per-model budget of a full
  live run, verified against a full run on 2026-08-29 (100 exchanges). Linked from README, PROJECT_STATE, COMPATIBILITY, ARCHITECTURE, ADR-025
  and the CONTRIBUTING testing rules (a new observation is a ledger row in the same PR as its test caveat).

### Fixed

- **`fanar-interceptor-logging`** — the wire log lost failures: when the rest of the chain threw
  (connection refused, timeout, a later interceptor), `WireLoggingInterceptor` had logged the `-->`
  block and nothing else. It now logs `<-- failed <uri> (<ms>ms): <exception class: message>` at the
  configured level and rethrows the exception unchanged — never swallowed
  ([ADR-012](docs/adr/012-interceptor-spi.md), amended 2026-08-29).
- **docs** — a full live run spends 11 `Fanar-Aura-TTS-2` calls, not 14 (five speech cases × 2 codecs plus one
  shared STT source clip per JVM); `LiveAudioSpeechTest` and PROJECT_STATE corrected.
- **docs** — ADR-014 said a connection dying mid-stream surfaces as an `ErrorChunk`; it surfaces as
  `onError` on the subscriber (an `ErrorChunk` is a server-sent error frame). Corrected, amended and proved.

## [0.3.0] - 2026-08-29

### Added

- **`fanar-core`** — `FanarQuotaExceededException.retryAfter()`: the server's `Retry-After`
  countdown now survives on both HTTP 429 subtypes, not only `FanarRateLimitException`
  ([ADR-025](docs/adr/025-retry-after-handling.md)).
- **`fanar-spring-boot-4-starter`** — `fanar.retry.max-delay` (default `30s`) and a
  `@ConditionalOnMissingBean RetryPolicy` bean: declare your own `RetryPolicy` bean for a custom
  `retryable` predicate, jitter or multiplier without replacing the `FanarClient` bean
  ([ADR-020](docs/adr/020-spring-boot-4-starter.md), amended).
- **`fanar-java-e2e`** — `LiveRateLimitHeadersTest`, a transport-level live pin of the
  rate-limit response-header contract (single codec; headers are codec-independent).

### Changed

- **`fanar-core`** — **retries now fire on HTTP 429 / 500 / 503 / 504** (see *Fixed*). Callers
  see the sleeps and re-requests ADR-014 always described; the default policy's worst case is
  two honoured `Retry-After` hints of up to 30 s each. `RetryPolicy.disabled()` opts out.
- **`fanar-core`** — `Retry-After` semantics ([ADR-025](docs/adr/025-retry-after-handling.md)):
  a hint is honoured up to `RetryPolicy.maxDelay()`; a larger hint ends retrying immediately and
  the exception surfaces with `retryAfter()` populated so callers can schedule around the wait.
  Non-positive, past-date or unparseable hints count as absent (computed backoff applies); a
  future HTTP-date becomes the remaining wait. The ceiling applies regardless of the `retryable`
  predicate — documented on `RetryPolicy`. `fanar.retry_count` is now recorded on every call
  (`0` included) and `http.status_code` per attempt.
- **`fanar-core`** — `RetryPolicy` rejects a `maxDelay` that is not representable in
  milliseconds at construction (previously an `ArithmeticException` at retry time).
- **`fanar-spring-boot-4-starter`** — `fanar.retry.initial-backoff` now defaults to `500ms`
  (was `100ms`), matching `RetryPolicy.defaults()` as the properties javadoc always claimed.
- **`api-spec`** — absorbed the 2026-08-27 Fanar spec refresh. Structurally a no-op (all 12
  operations, 97 schemas, and the per-model rate-limit table are unchanged; `info.version`
  still 1.0.0); the refresh documents the previously-unspecified rate-limit response headers:
  `x-ratelimit-limit` / `x-ratelimit-remaining` / `x-ratelimit-reset`, `ratelimit-policy`
  (`limit;w=seconds` — the only way to distinguish a per-minute from a per-day window), and
  `retry-after` (429-only, in seconds). Verified live 2026-08-27: chat 2xx responses carry all
  four quota headers (`50;w=60` on chat models); `GET /v1/models` and 401 responses carry none.
  2026-08-28: an exhausted per-day TTS window (`20;w=86400`) answered 429 `rate_limit_reached`
  with `retry-after` equal to `x-ratelimit-reset` (~8 h) — surfaced immediately by the ADR-025
  ceiling. Typed exposure of the window headers is deferred (see PROJECT_STATE); read them via
  the `Interceptor` SPI.

### Fixed

- **`fanar-core`** — HTTP-status retry never fired. Every domain facade mapped 4xx/5xx to the
  typed hierarchy *after* the interceptor chain returned, so the built-in `RetryInterceptor`
  only ever retried transport failures — ADR-014's retryable set was dead end-to-end in 0.1.0
  and 0.2.0. The mapping now happens inside the chain at the retry boundary
  ([ADR-012](docs/adr/012-interceptor-spi.md) and
  [ADR-006](docs/adr/006-unchecked-exception-hierarchy.md), amended); user interceptors still
  see raw error responses. Covered end-to-end by `FanarClientRetryIntegrationTest` — the public
  builder against a local `HttpServer` scripting 5xx / 429 / `Retry-After` sequences — plus a
  facade-level test driving 503 → 200 through the real chain.

## [0.2.0] - 2026-08-06

Full parity with the 2026-08 Fanar spec: madhab-aware `Fanar-Sadiq-2`, custom personas,
streamed + emotional TTS, a rich voice catalogue, culturally-aligned image prompt revision,
and typed envelope-code error routing. Pre-1.0 ([ADR-019](docs/adr/019-pre-10-stability-policy.md)):
this release contains three breaking changes, marked below. Not yet on Maven Central — install
via `./mvnw install` from a clone, or download the artifacts attached to this release.

### Added

- **`fanar-core`** — streaming TTS: `AudioClient.speechStream(request)` returns
  `Flow.Publisher<byte[]>` and delivers the audio chunked as the server generates it
  (the wire `stream:true` mode, mp3 + wav). Single-subscriber, back-pressured, cancel closes
  the connection — the same contract as chat streaming.
  ([ADR-023](docs/adr/023-streaming-tts-via-flow-publisher.md))
- **`fanar-spring-ai-starter`** — `FanarTextToSpeechModel.stream(...)` now streams for real:
  one `TextToSpeechResponse` per audio chunk via `speechStream`, replacing the previous
  single-element-Flux emulation.
- **`fanar-core`** — audio: `Voice.ABDULRAHMAN` and `Voice.RADWA` (the two emotion-capable
  built-ins), `TextToSpeechRequest.withEmotion` (emotional synthesis — `Fanar-Aura-TTS-2` +
  emotion-capable voices only, otherwise HTTP 422) plus a fluent
  `TextToSpeechRequest.builder()`, and the rich voice catalogue types `AvailableVoice` /
  `VoiceType` returned by `listVoices()`.
- **`fanar-core`** — `ChatModel.FANAR_SADIQ_2` (madhab-aware Islamic RAG, extra authorization
  required) plus two new `ChatRequest` fields: `persona` (custom assistant voice/identity,
  `Fanar-Sadiq` only, ≤ 2000 chars) and `madhab` (list of the new open value class `Madhab`:
  `ALL` / `HANAFI` / `MALIKI` / `SHAFII` / `HANBALI`, honoured by `Fanar-Sadiq-2`).
  Both codecs serialize `Madhab` via their wire-value modules.
- **`fanar-core`** — images: `ImageGenerationRequest.revise` (server default **true** — automatic
  prompt revision for style/quality/cultural alignment; pass `false` to keep the prompt verbatim).
- **`fanar-spring-ai-starter`** — `FanarImageGenerationMetadata(revised, revisedPrompt)` attached
  to every `ImageGeneration`, and the previously-dropped `created` timestamp now fills
  `ImageResponseMetadata`.
- **`fanar-spring-ai-starter`** — vendor options
  ([ADR-024](docs/adr/024-spring-ai-vendor-options.md)): `FanarChatOptions` (persona, madhab,
  thinking mode, Islamic-RAG scoping, logit bias, and the vLLM sampling knobs — all previously
  unreachable through portable `ChatOptions`), `FanarTextToSpeechOptions` (`withEmotion`,
  `quranReciter`), and `FanarImageOptions` (`revise`). `FanarChatOptions.Builder` extends
  Spring AI's `DefaultChatOptionsBuilder`, so the extras survive the `ChatClient`
  `mutate()`/`combineWith()` pipeline.
- **`fanar-core`** — `ErrorCode.CLIENT_CLOSED_REQUEST` and `FanarClientClosedRequestException`
  (HTTP 499, `client_closed_request`), which the 2026-08 Fanar spec declares on every endpoint.
  Correctly classified as non-retryable; previously a 499 fell into the generic 5xx fallback and
  was retried.

### Changed

- **`fanar-core`** — `ExceptionMapper` now parses the Fanar error envelope and routes by the typed
  `error.code` first, falling back to HTTP status for non-envelope bodies. `FanarQuotaExceededException`
  is now reachable (previously every HTTP 429 surfaced as `FanarRateLimitException`), and a
  non-filter 400 no longer surfaces as `FanarContentFilterException`. Exception messages now carry
  the envelope's `message` instead of the raw JSON body when available.
  ([ADR-006 amendment](docs/adr/006-unchecked-exception-hierarchy.md))
- **Breaking** — `ImageGenerationItem` is now
  `(String b64Json, boolean revised, String revisedPrompt)` (was single-component), matching the
  spec's now-required response fields.
- **Breaking** — `VoiceResponse.voices()` is now `List<AvailableVoice>` (was `List<String>`),
  matching the 2026-08 spec's rich voice objects; the listing now always includes the built-in
  public voices, not only personalized ones. Use `AvailableVoice.name()` where the raw string
  was used before.
- **Breaking** — `FanarClientException` gained a ninth permitted subtype
  (`FanarClientClosedRequestException`). Exhaustive `switch` expressions over the leaves of
  `FanarClientException` no longer compile until the new case is added; switches over the four
  top-level `FanarException` branches are unaffected. Allowed pre-1.0 per
  [ADR-019](docs/adr/019-pre-10-stability-policy.md).
- **Spec** — `api-spec/openapi.json` refreshed to the 2026-08-05 Fanar spec; added
  `api-spec/openapi.yaml`, its YAML twin (JSON remains normative).

## [0.1.0] - 2026-04-28

Initial public release. Pre-1.0; not yet on Maven Central — install via `./mvnw install`
from a clone, or download the artifacts attached to this release.

### Added

- **`fanar-core`** — typed `FanarClient` with eight domain facades
  (`chat` / `models` / `tokens` / `moderations` / `translations` / `poems` / `images` / `audio`),
  sealed `FanarException` hierarchy, SSE streaming via `Flow.Publisher<StreamEvent>`,
  retry policy with jitter, interceptor chain, observability SPI. Sync + async + streaming
  on every domain. 100 % JaCoCo coverage. Zero runtime dependencies.
- **JSON codecs** — `fanar-json-jackson2` (Spring Boot 3 / Jackson 2) and
  `fanar-json-jackson3` (Spring Boot 4 / Jackson 3). `ServiceLoader` discovery, GraalVM
  reachability metadata.
- **Observability adapters** — `fanar-obs-slf4j`, `fanar-obs-otel`, `fanar-obs-micrometer`.
  Wire any combination via `ObservabilityPlugin.compose(...)`.
- **`fanar-interceptor-logging`** — OkHttp-style wire-logging interceptor with
  `NONE` / `BASIC` / `HEADERS` / `BODY` levels, SLF4J sink, header redaction,
  body byte cap, streaming-aware.
- **`fanar-spring-boot-4-starter`** — `@AutoConfiguration` registering `FanarClient`
  from typed `fanar.*` properties; auto-wired `Interceptor` / `ObservabilityPlugin`
  beans; `FanarHealthIndicator` activated when `spring-boot-health` is on the classpath.
- **`fanar-spring-ai-starter`** — Spring AI 2.0 (pinned to `2.0.0-M4`) `ChatModel` +
  `StreamingChatModel` + `ImageModel` + `TextToSpeechModel` + `TranscriptionModel`
  adapters. Memory + RAG advisors compose via Spring AI's `ChatClient`.
- **Sample apps** (cloneable, not shipped as release artifacts) —
  `fanar-spring-boot-4-sample` and `fanar-spring-ai-sample`. Run with
  `FANAR_API_KEY=… ./mvnw -pl <module> spring-boot:run`.
- **GraalVM native-image** — reachability metadata for the 38 records the JSON codec
  touches; `e2e-graalvm` module with self-test + live-walk modes; PR-time native smoke
  workflow; bootstrap workflow for re-tracing metadata.
- **Live e2e suite** (`fanar-java-e2e`) — parameterised over both codecs across every
  domain, gated on `FANAR_API_KEY`; offline by default, opt-in for live runs.
- **CI** — Java 21 + 25 build matrix, JaCoCo 100 % gate, `dependency:analyze` strict
  mode, doclint at javac time, JaCoCo report uploaded as artifact on failure for
  flake diagnosis, doc-link verification.
- **`fanar-java-bom`** — version alignment for multi-module consumers.

### Known limitations

- **Spring AI `ModerationModel`** — not implemented. Fanar's moderation returns
  continuous safety + cultural-awareness scores; Spring AI's surface expects 16
  category booleans. Use `FanarClient.moderations()` directly. ([rationale](docs/adr/021-spring-ai-2-adapter.md))
- **Spring AI `EmbeddingModel`** — not implemented. Fanar exposes no embeddings
  endpoint. RAG users bring an external embedder
  (`spring-ai-openai`, `spring-ai-transformers`, etc.).
- **User-supplied tool calling** — Fanar rejects user `tools` / `tool_choice`
  server-side. Spring AI's tool-callback advisors degrade silently in our adapter.
- **Native chat structured output** — Fanar exposes no `response_format` field.
  Spring AI's prompt-engineering converters (`BeanOutputConverter`) still work
  end-to-end since they shape the prompt text.
- **Fanar `stop` parameter** — silently dropped server-side; documented in tests.

[Unreleased]: https://github.com/omahjoub/fanar-java/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/omahjoub/fanar-java/releases/tag/v0.6.0
[0.5.0]: https://github.com/omahjoub/fanar-java/releases/tag/v0.5.0
[0.4.0]: https://github.com/omahjoub/fanar-java/releases/tag/v0.4.0
[0.3.0]: https://github.com/omahjoub/fanar-java/releases/tag/v0.3.0
[0.2.0]: https://github.com/omahjoub/fanar-java/releases/tag/v0.2.0
[0.1.0]: https://github.com/omahjoub/fanar-java/releases/tag/v0.1.0
