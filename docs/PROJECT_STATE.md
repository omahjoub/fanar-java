# Project state

> **Snapshot — 2026-09-17.** Last release 0.6.0 (2026-09-16); `main` is on 0.7.0-SNAPSHOT. Updated on every
> milestone. If this looks wrong or stale, that is the signal — update it in the same PR as whatever moved.

## Phase

**0.6.0 released 2026-09-16** ([v0.6.0](https://github.com/omahjoub/fanar-java/releases/tag/v0.6.0),
GitHub Release, 10 artifacts) — "reconciliation". Every document was checked against the code rather
than against other documents, and the disagreements were defects more often than drift. Five were
consumer-facing, two of them making whole integration paths unusable: `chat().stream()` never worked
under GraalVM native-image (no `StreamEvent` was in the reachability metadata) and
`fanar-spring-boot-4-starter` could not be placed on the module path at all (JPMS derives the invalid
component `4` from the filename). Also: `fanar-spring-ai-starter` was published but missing from the
BOM in every release since v0.1.0; an undeclared HTTP status surfaced as a fabricated `500` after
three retries; and an `ObservabilityPlugin` could fail the call it observed — but only when installed
alone, so composing more plugins made you safer. New: `Streams.toStream(...)`
([ADR-005](adr/005-streaming-via-flow-publisher.md)) and two `Unexpected*` exception leaves
([ADR-006](adr/006-unchecked-exception-hierarchy.md)). All 29 ADRs now match the code and **none
carries an amendment** ([ADR-019](adr/019-pre-10-stability-policy.md)); ADR-029 records the
publication decision, which gates 1.0.0. Two new CI gates (`check-docs`, `check-build`) and a GraalVM
for JDK 21 + 25 matrix. **Breaking** — observation names, streaming-observation lifecycle, unmapped
status mapping; see [CHANGELOG](../CHANGELOG.md).

Before it, **0.5.0 (2026-09-15)** — "full coverage of the published surface". The 2026-09 spec refresh is
absorbed: one new operation (12 → 13; schemas 97 → 100) became a **ninth domain facade**,
`client.sadiq().validate(...)` over `POST /v1/sadiq/validate`, verifying the Qur'anic verses and hadith
quoted in arbitrary text and returning them tagged and cited. The returned text is the wire string
verbatim — the SDK does not parse the markup ([ADR-028](adr/028-sadiq-validation-facade.md)). The
endpoint requires additional authorization, so its live cases fail loudly until the key is upgraded.
Plus `FanarContentFilterException.filterType()` fixed from dead public API
([ADR-006](adr/006-unchecked-exception-hierarchy.md)), an internal envelope-parser
robustness fix, and a corrected scope claim — the chat endpoint **accepts and silently ignores**
user `tools` rather than rejecting them, which is what makes the Spring AI adapter's silent
degradation the right behaviour ([ADR-021](adr/021-spring-ai-2-adapter.md)). **No breaking
changes**, unlike 0.4.0. See [CHANGELOG](../CHANGELOG.md).

Before it, **0.4.0 (2026-08-30)** — "proof over coverage": every behaviour an ADR promises is proved
through the public API by a named seam-crossing `*IntegrationTest`; live behaviour is dated and pinned
in the [wire-observations ledger](WIRE_OBSERVATIONS.md); the retry boundary publishes the server's
rate-limit window (ADR-026) and stops sleeping past a total budget (ADR-027, that release's one
breaking change).

**Landed on `main` for 0.7.0 — `fanar-adk`, the Google ADK Java adapter**
([ADR-030](adr/030-google-adk-adapter.md)). A `BaseLlm` over `FanarClient`, layered directly on
core with the Jackson 2 codec at runtime scope (ADK already carries Jackson 2), so one dependency
plus ADK gives ADK agents, the dev UI, sessions, callbacks and plugins on Fanar models. Transport,
retry, auth and observability stay on `FanarClient.builder()`; the client can be built lazily so a
missing key or codec no longer vanishes into ADK's agent loader. Features Fanar cannot honour —
tool declarations (including the transfer tool ADK injects for multi-agent trees), output schemas,
unmapped parts — are refused before the wire by default rather than dropped silently, the opposite
of the Spring AI choice, because in ADK the runner's loop is function calling; workflow steps and
leaf specialists need no opt-in. Sadiq references become ADK grounding metadata. The published set
grows to ten library jars plus the BOM. Untouched: core. The `Fanar-Agentic` model gate is now
pinned by `LiveAgenticGateTest`, which passes while the gate holds and goes red the day the key is
granted — the trigger to probe user tools by hand and reopen ADR-021, ADR-024 and ADR-030.

**Open on `main` (0.7.0-SNAPSHOT).** One item shipped untriaged: `LivePoemsTest` overran its 3×
verse-match tolerance on the 2026-09-15 run, so a full live run produces **10 failures, or 11 when
a run's Diwan misses concentrate on one case** — the ten gated by design, plus that one. Miss rate
by date: 2/4, 2/6, 0/4, 4/7, 4/8 — high, but not trending; 2026-09-15 and 2026-09-16 both saw four
misses and only the first failed, because three landed on one case. Either the retry budget rises or
`LivePoemsTest` joins the known-failing list; deferred 2026-09-16 until the upgraded
API key lands, which turns the ten gated failures green and rewrites the list in the same pass. The
[ledger](WIRE_OBSERVATIONS.md#live-suite-budget) records 11 until then. Also carried: the live-suite nightly and the
publication decision ([ADR-029](adr/029-publication-target.md)), both waiting on the Fanar team.
**Confirmed outstanding 2026-09-16**: the API-key enhancement was requested and has had no reply.
Nothing in this repository records the state of that conversation, so re-date this line rather than
trusting it — what the key can *do* is checkable without asking anyone; see the nightly item below.

## Planned

- **Where the artifacts get published — an open three-way decision, recorded in [ADR-029](adr/029-publication-target.md) (Proposed).** Today they ship
  as GitHub Release assets: downloadable, but not resolvable as a dependency.
  **The forcing constraint is the coordinate.** `groupId` is `qa.fanar`, reverse-DNS for `fanar.qa`,
  and Sonatype Central verifies a namespace by proving control of the domain. That domain is the
  Fanar team's, so this project cannot publish its current coordinates to Central on its own. The
  same claim sits in the package names (`qa.fanar.*`).
  1. **Fanar creates and verifies the `fanar.qa` namespace, then grants publish rights.** Best
     outcome, zero coordinate churn — everything already written stays correct. Checked 2026-09-16:
     **the namespace does not exist on Central**, so this is Sonatype onboarding on their side, not
     a permission toggle. Depends on their willingness, their timeline, and on whether they want a
     third-party SDK under their namespace. This is the open ask.
  2. **GitHub Packages.** Available unilaterally and immediately, works with any groupId. But
     consumers must add a `<repository>` *and* authenticate — GitHub requires a token even for
     public packages — which contradicts the "no custom repositories" rule in
     [JLBP-21](JAVA_LIBRARY_BEST_PRACTICES.md). Best understood as an interim channel that does not
     foreclose 1 or 3, rather than a destination.
  3. **Our own Central namespace** (`io.github.omahjoub`). Available unilaterally, verification is
     quick. Costs a groupId change across every coordinate, the BOM and the docs — and raises
     whether the `qa.fanar.*` **package** root should follow, since it claims the same domain.
     Cheap now, a fork after 1.0.
  **Timing:** (3) is the only path with a deadline. Renaming coordinates and packages is a
  find-and-replace before 1.0 and a breaking change after it, so this must be settled before the
  ADR-019 freeze opens, whichever way it goes.
  Path-independent prerequisites are **done**: `-sources.jar` and `-javadoc.jar` per published
  module under `-Ppublish`, and reproducible builds via `project.build.outputTimestamp` (verified:
  two builds of one commit produce a byte-identical jar). Still path-dependent and therefore not
  done: `distributionManagement`, and GPG signing (required by Central, not by GitHub Packages).
- **Surface `X-Revised-Input` from `POST /v1/audio/speech`** — the 2026-09 spec expanded the header to cover hadith and to state that tags and citation links are stripped before synthesis, but `speech()` returns `byte[]` and drops every response header. Exposing it changes the return type or adds a sibling method, and should follow ADR-026's principle that response metadata travels on observations and exceptions rather than on DTOs. Deferred out of the spec sync as its own decision (ADR-028, Alternatives).
- **Readable request-validation errors** — Fanar returns two error shapes. App-level errors use the documented `{"error":{…}}` envelope; **request-validation failures bypass it entirely** and return FastAPI's `{"detail":[{"loc":["body","model"],"msg":"Input should be …"}]}` (observed 2026-09-15, [ledger](WIRE_OBSERVATIONS.md#error-envelope-shape)). The SDK routes those correctly by HTTP status, but the exception message is the raw JSON blob. Parsing `detail[].loc` / `msg` into a readable message — and deciding whether the offending field gets a typed surface — is the natural home for the `param` question below.
- **Error-envelope `param` on the public API** — parsed into the internal envelope as of 0.5.0, deliberately not surfaced: it belongs on `Error`, i.e. on *every* exception, so a `FanarException.param()` accessor means new constructor overloads down every leaf subtype. The 2026-09-15 probe argues against ever paying that: `param` is `null` on every envelope captured, and the errors that *would* name a field don't use the envelope at all. If a typed surface is ever wanted, take it from FastAPI's `loc` in the item above, or use an observation attribute (ADR-026's precedent).
- **Spring Boot 3 starter** — `fanar-spring-boot-3-starter` with the Jackson 2 codec; mechanical port of the SB4 starter.
- **LangChain4j adapter** — `fanar-langchain4j` exposing the equivalent of Spring AI's adapters against LangChain4j's `ChatLanguageModel`. Ordered after the Google ADK adapter, whose integration cost was measured first ([ADR-030](adr/030-google-adk-adapter.md)).
- **Quarkus extension** — CDI beans, build-time wiring, native-image friendliness.
- **Nightly live e2e on CI** — scheduled job runs `fanar-java-e2e` with the `FANAR_API_KEY` secret (it exists; today only `graalvm.yml`'s manual bootstrap job uses it); PR builds stay offline. Parked 2026-08-30 pending a higher-quota key from the Fanar team: on the standard key a full run spends 11 of `Fanar-Aura-TTS-2`'s 20 per trailing 24 h ([budget table](WIRE_OBSERVATIONS.md#live-suite-budget)), so the nightly would have to be the only full run within 24 h, and it must exclude the ten known-gated cases (six, plus four for the gated validation endpoint since 2026-09-15) or stay red every night.
  **How to tell whether the key has since been upgraded, without asking:** the gated cases are the
  test. With `FANAR_API_KEY` set, run
  ```
  ./mvnw -pl e2e -am test -Dtest='LiveSadiqValidateTest,LiveAudioVoicesTest' -Dsurefire.failIfNoSpecifiedTests=false
  ```
  Those failures are 403s rejected *before admission*, so they consume no quota and the run is safe
  at any time. **Check the count, not the exit code**: you want `Tests run: 5`. Separate the classes
  with a comma — Surefire does not treat `+` as a separator, so a `+` selects nothing and still
  reports `BUILD SUCCESS`. `Skipped: 5` means the key was not visible to the JVM. Green means the
  permissions were granted. For the quota, read `x-ratelimit-limit` on a TTS call in the
  `fanar.wire` log: `20` is the standard key. Whatever this file says, that run is the current
  answer.

## Deferred (won't fit cleanly)

- **Spring AI `ModerationModel`** — Fanar's `/v1/moderations` returns continuous `safety` + `culturalAwareness` scores; Spring AI's `Categories` is a fixed set of boolean flags (19 in Spring AI 2.0.1). A best-effort mapping would always report `Categories.isHate()=false`, which is misleading. Surfaced via `FanarClient.moderations()` directly instead.
- **Spring AI `EmbeddingModel`** — Fanar exposes no `/v1/embeddings` endpoint at all. Users wanting RAG bring their own embedder (`spring-ai-openai`, `spring-ai-transformers`, etc.).
- **Native `response_format` / structured output on chat** — not in the Fanar wire spec. Spring AI's prompt-engineering converters (`BeanOutputConverter`) still work because they shape the prompt text, not the request flag.
- **User-supplied tool calling** — Fanar's `/v1/chat/completions` **accepts `tools` / `tool_choice` and silently ignores them.** Live-proved 2026-09-15: HTTP 200, `tool_calls` empty, the tool name absent from the body, and `prompt_tokens` **identical** with and without the array — so it is discarded at the edge and never reaches the model ([ledger](WIRE_OBSERVATIONS.md#chat-completions--post-v1chatcompletions)). Until 2026-09-15 this entry read *rejects*, inferred from the schema and never actually sent. A caller therefore gets no signal, which is why Spring AI tool callbacks degrade silently in our adapter ([ADR-021](adr/021-spring-ai-2-adapter.md), [ADR-024](adr/024-spring-ai-vendor-options.md)). The `tool_calls` events in streams remain server-internal Sadiq retriever telemetry. **Still open:** `Fanar-Agentic` and `Fanar-Sadiq-Agentic` answer 422 "Model not authorized" for this key, so whether an agentic variant accepts user tools is untested — the one finding that would reopen this scope, and part of the pending key request. Since 2026-09-17 the gate is pinned by `LiveAgenticGateTest`, which asserts the 422 and goes red when it lifts. The Google ADK adapter refuses tool declarations and output schemas by default instead of degrading, because in ADK the runner's loop is function calling ([ADR-030](adr/030-google-adk-adapter.md)).
- **`ChatModel` constants for `Fanar-Agentic`, `Fanar-Sadiq-Agentic`, `Islamic-RAG`** — investigated and declined 2026-09-15. The server's validation enum accepts all three, but calling them settled it: the two `-Agentic` ids are gated for our key (422 "Model not authorized") and `KNOWN` is a claim about what *works*, while `Islamic-RAG` is an **alias** for `Fanar-Sadiq` — same routed backend, same `prompt_tokens`, same references, same quota counter. `ChatModel` equality is by wire string, so an `ISLAMIC_RAG` constant would compare unequal to `FANAR_SADIQ` while behaving identically. `ChatModel.of(…)` already reaches all three (ADR-015 open value record). Revisit the `-Agentic` pair only if the key is upgraded. Evidence in the [ledger](WIRE_OBSERVATIONS.md#chat-completions--post-v1chatcompletions).
- **Fanar `stop` parameter** — silently dropped server-side; dated in the [wire observations](WIRE_OBSERVATIONS.md).
- **Rate-limit headers on response DTOs** — shipped in 0.4.0 as observation attributes and as `RateLimitInfo` on the 429 exceptions instead ([ADR-026](adr/026-rate-limit-telemetry.md)); putting them on every DTO would touch the whole ADR-015 grid for information those two surfaces already carry. A proactive throttle stays a user-supplied interceptor (ADR-012).

## Cadence for updates

Update this file when:

- A milestone ships (new module, new framework adapter, version-tag, public release).
- An ADR gets superseded.
- A `Planned` item moves to `Shipped`, or a `Deferred` item gains traction.

Commit the update in the same PR as the change that motivated it — never separately.
