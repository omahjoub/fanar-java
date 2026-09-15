# Lessons — patterns to reuse (and mistakes not to repeat)

## From the 0.2.0 spec-absorption cycle (2026-08)

- **Diff specs structurally, never by eye.** A python deep-diff of old-vs-new OpenAPI (signal
  vs doc-noise) found everything in minutes and proved the YAML twin was semantically identical.
  Reuse the approach for every future spec refresh.
- **Never trust "the server will answer 4XX" predictions — verify the wire shape live.** Two
  assumptions died on contact: Fanar's model gate answers 422 "Model not authorized" (not 403),
  and `/v1/models` is visibility-scoped (never assert `KNOWN ⊆ listing`). Write live-test
  caveats with dates and the *observed* shape, not the inferred one.
- **Live-test tolerance needs categories, not a blanket rule.** Fail-loudly stays the default;
  the two justified exceptions found so far are (a) documented nondeterministic *semantic*
  outcomes (Diwan verse-miss → bounded retry on exactly that exception) and (b) spec-documented
  model gating (skip-from-assertion with a dated note). Auth/timeout/transport always fail.
- **When a hardcoded grid exists (null-padded ctor calls, reflect-config accessor lists,
  KNOWN-count asserts), enumerate every one before changing a record.** The 100 % JaCoCo gate
  plus compile errors catch the rest — budget for the three-layer update: core + both codecs +
  reachability metadata + graalvm probes.
- **Check the framework pipeline before implementing an SPI.** `ChatClient` rebuilds options via
  `mutate().build()`; implementing `FanarChatOptions` naively would have silently dropped every
  vendor knob. Read the consuming framework's source (sources jars in ~/.m2) for the call path,
  and javap the *exact resolved dependency version* — a GA jar and a milestone jar had different
  interface contracts in the same session.
- **Process docs must be runbooks, not narratives.** The release process only became replayable
  once it was a parameterized checklist (`VERSION=`/`NEXT=` + checkboxes) with a
  self-improvement rule ("a release surprise fixes the runbook in the same PR"). README version
  snippets went stale after two consecutive releases until a checklist line owned them.
- **Same-PR cadence works.** PROJECT_STATE/CHANGELOG updated in the same change as the code
  they describe never went stale; everything updated "later" did.

## From the 2026-08-27 spec-refresh review (2026-08-28)

- **Persist plans and expensive-to-regenerate state to `tasks/todo.md` the moment they exist.** A session
  limit killed a `/code-review max` orchestrator mid-run; the resumed run's ~2M-token findings would have
  vanished with the context. Findings ledgers, pending decisions and a restart protocol belong on disk,
  not in chat — and the restart note must say "do not re-run X" where X is the expensive step.
- **Prove a feature end-to-end through the public facade, not just the unit under test.** `RetryInterceptor`
  had 100 % coverage and 16 green tests while HTTP-status retry had never fired through `FanarClient`
  (facades map 4xx/5xx *after* the chain returns). Unit tests that inject the expected exception directly
  prove the loop, not the wiring; every `*ClientImplTest` used `RetryPolicy.disabled()`, so nothing crossed
  the seam. Before documenting a behaviour change, reproduce the *old* behaviour once.
- **Read `ratelimit-policy` before diagnosing "rate-limits fast".** The audio endpoints were believed to
  throttle chained calls "in quick succession" (2026-04-25 note); the first live 429 captured
  (2026-08-28) showed the real mechanism: a **20 requests/day** budget per TTS model, and a full e2e run
  spends 14. The window, not the pace, was the constraint — and the earlier belief that "the retry
  policy absorbed it" was impossible (retry never saw a 429 before 0.3.0). One header answers what a
  week of guessing did not.

## From 0.4.0 Phase 1 — seam-crossing tests (2026-08-29)

- **Survey before you generalise a fixture, then build the fixture before test #1.** A read-only subagent survey of
  the 121 test classes (file:line evidence) found the server boilerplate ×7, `clientFor` ×5, seven discarded
  `latch.await` results and zero `@Timeout` in minutes; two of its claims were wrong on inspection
  (`assertThrows(RuntimeException.class …)` was a legitimate propagate-unchanged contract; `@Tag("live")` *is*
  filterable via `-Dgroups` without pom config). Verify the load-bearing claims of any survey before acting on them.
- **JUnit configuration parameters supplied by the launcher (Surefire `configurationParameters`) beat `-D` system
  properties.** Two "experiments" that overrode the timeout with `-Djunit.jupiter.execution.timeout.default=2s`
  were silently ignored because the pom value won; they proved nothing. Experiment through the same channel the
  production config uses, and make every probe self-limiting (`await(20 s)` + a self-limit assertion) so a wrong
  hypothesis costs seconds, not a 5-minute tool timeout with an orphaned fork to kill.
- **A one-off hang is not a conclusion.** The first timeout probe ran 5 min once; the exact same probe then failed
  at 60.04 s twice. Reproduce before re-designing — and take a `jstack` at the guard time on the re-run.
- **Surefire on the JPMS module path places `provides` modules on the module path, not the classpath.** The Jackson 3
  codec is a `FanarJsonCodec` provider, so as a plain test dep of an obs module it landed on the module path with
  its `provided` Jackson runtime missing, and once that was added the patched test module could not *read* it.
  The fix that is also the honest seam: declare the runtime at test scope (whitelisted like `slf4j-nop`) and let
  `FanarClient.builder()` discover the codec via `ServiceLoader` — no import, no `--add-reads`.
- **A reactor test fixture changes the documented commands.** Once core depends on `test-support` (test scope),
  `./mvnw -pl core test -Dtest=X` cannot resolve it; the stateless form is `-pl core -am test -Dtest=X
  -Dsurefire.failIfNoSpecifiedTests=false`. Update CONTRIBUTING/CLAUDE.md in the same PR as the dependency.
- **Run the fixture's own consumer before trusting it.** Two bugs in `ScriptedHttpServer`/`CollectingSubscriber`
  (case-insensitive header replace kept the old key spelling; `closeExceptionally` may drop a buffered item)
  surfaced in the self-tests, and a script-generated edit missed an import because the check ran after the
  replacement it was checking for — assert what the file *should* contain, not what the script just wrote.
- **Behaviour claims in ADRs get proved or corrected, never left as prose.** ADR-014 said mid-stream drops surface
  as an `ErrorChunk`; the seam test showed `onError(IOException)`. The "proved by" annotation forces the
  comparison; a wrong claim becomes a dated amendment in the same PR.


## From 0.4.0 Phase 2 — wire-observations ledger (2026-08-29)

- **Count budgets from the code, and show the derivation next to the number.** "A full run spends 14 TTS calls
  (7 call sites × 2 codecs)" was written into a javadoc on 2026-08-28 and copied into PROJECT_STATE and memory;
  the file had five cases then and now, and the shared STT clip is per JVM, not per codec — the real figure is 11.
  A number without its derivation cannot be checked; the ledger's budget table carries the formula per row.
- **Re-read the spec before repeating a "spec says" claim.** Three live-test javadocs still cite the 2026-04 spec's
  "requires additional authorization" for images / translations / poems; the 2026-08 spec dropped it for those
  endpoints and added it for TTS / STT / voices instead. The ledger's "Spec says" column is quoted from the current
  `api-spec/openapi.json` (a 20-line python over `paths` + `x-codeSamples`), never from an older caveat.
- **Migrating knowledge out of memory means leaving pointers, not deleting.** Each observation memory now holds
  the date, the ledger section and the "how to apply" line only; recall still matches on the description while the
  wire detail has one home that reviewers can see.
- **A full live run is the only real test of a ledger — analyse the wire log offline, key never in the session.**
  The user ran the suite in their own terminal; the `BODY`-level wire log (token redacted, SSE bodies and base64
  tails elided) parsed into 100 exchanges in a 60-line script and falsified two "observations" that three sessions
  had repeated (`x-ratelimit-reset` "constant 60"; voices "all three fail"). Header *sequences* with timestamps —
  not single values — are what reveal the mechanism (sliding window: `reset` jumped 2 → 8 with `remaining` flat).

## From 0.4.0 Phase 3 — wire-log throw path (2026-08-29)

- **Know where the body is read before designing a failure test.** `BodyHandlers.ofInputStream()` returns at the
  headers, so a server that drops the connection mid-body does not throw inside the interceptor chain at all — the
  failure surfaces in whoever reads the stream later. The throw path an interceptor can observe is connection-level
  (`FanarTransportException`) or a later interceptor; a started-then-closed `ScriptedHttpServer` port gives a
  deterministic "connection refused" without a new fixture.

## From 0.4.0 Phase 4 — rate-limit visibility (2026-08-29)

- **Record telemetry where every attempt passes, not where the result lands.** Putting the `fanar.ratelimit.*`
  recording next to `http.status_code` in `RetryInterceptor` gave "last attempt wins" and "the retried 429's window
  is still recorded" without any extra code; the facades would have seen only the final response. Ask "which
  frame sees every attempt?" before choosing where an attribute is written.
- **One parser, two surfaces.** `RateLimitHeaders.parse` feeds both `rateLimit()` and the attributes, so a seam
  test that asserts the two agree is trivially true and a future header quirk gets fixed in one place.

## From 0.4.0 Phase 5 — RetryPolicy budget + builder (2026-08-29)

- **A new cross-field invariant bites the existing tests first.** Adding `maxTotalDelay ≥ maxDelay` broke two
  tests that raised `maxDelay` alone (a boundary test, and my own seam test). Before adding `a ≥ b`, grep for every
  test and sample that sets `b` in isolation and decide whether the ergonomics ("raise both") are acceptable — here
  they are, and the trade-off is written into the ADR — or whether the default should adapt.
- **Read the target before writing a regex for it.** A blind regex edit asserted on zero matches, left the tree
  untouched, and the verify that followed re-ran the same failures. Cheap here; the rule from Phase 1 stands.

## From 0.4.0 Phase 6 — facade plumbing consolidation (2026-08-29)

- **Check the package graph before following a plan's suggested location.** The plan put the dispatcher in
  `internal.transport`; `RetryInterceptor` already imports from there, so that would have created a package cycle.
  A new leaf package (`internal.dispatch`) kept the layering acyclic at no cost — worth a one-line note in the
  review so the deviation is deliberate, not drift.
- **Transform N copies with one asserting script, and let the assertion fire before the write.** Eight facades
  were rewritten by one regex script that asserts each anchor occurs exactly once per file and writes only at the
  end; the one mismatch (nested parentheses at a single call site) stopped the run with the tree untouched.

## From the Phase 7 decision (2026-08-30)

- **Before optimizing around a constraint, ask whether the constraint can be lifted.** Phase 7 planned single-codec
  audio tests, a budget tag and a nightly around a 20-per-day TTS window; the user's move was to ask the provider for
  a bigger key first and only optimize if refused. Cheaper, and it might also clear the gated cases the tests fail
  on today. Put the ask in the plan with a fallback so the parked phase resumes without re-deciding.

## From the 0.4.0 release (2026-08-30)

- **"A live run was performed" must mean "on the tree being tagged".** 0.4.0 was tagged with the 2026-08-29 run as
  its evidence although four PRs of core changes landed after it. The runbook now says release-candidate tree
  explicitly; the fix costs one live run per release and nothing else.

## From the 2026-09 spec sync — `POST /v1/sadiq/validate` (2026-09-15)

- **Present decisions, then ask — never ask cold.** Two batches of options were rejected before the
  user said what they wanted: the reasoning written out first, each choice explained with its
  trade-off, *then* the question. "Here are four questions" reads as offloading the thinking; "here
  is what I found, here is what I'd do and why, where do you disagree" is the same information in
  the order a reviewer can act on. When a rejection says *clarify*, the next move is to ask what
  they want clarified — not to re-ask a better-worded version of the same thing.
- **Copy the sibling that matches the shape, not the one that looks nearest.** `SadiqValidationResponse`
  was modelled on `SafetyFilterResponse` and shipped with no null checks; `SafetyFilterResponse` is
  the *only* response record without them, precisely because its `id` is a documented nullable
  server quirk. The real twin was `TranslationResponse(String id, String text)` — identical shape,
  both fields `required` in the spec, both enforced. Before cloning a template, check whether the
  thing you are copying is the convention or the exception: grep the whole family, don't stop at one.
- **A count corrected in one file is a count still wrong in four.** "Known-failing 6 → 8" was fixed
  in the ledger, PROJECT_STATE and CHANGELOG but survived in ADR-028, which had been written earlier
  — and the ADR is the document the nightly-job exclusion list will actually be read from. When a
  number changes, grep it repo-wide *after* the fix, not before.
- **Grep for stale counts beyond Markdown.** The endpoint count and the facade list were also baked
  into `docs/images/*.svg` and a documented sample output in `docs/GRAALVM.md`. `check-docs` only
  validates that relative `.md` links resolve — it checks no counts, no anchors, and no non-`.md`
  targets. Diagrams and pasted console output are documentation too.
- **Verify the "no change needed" predictions, don't just assert them.** The plan claimed reusing
  `ChatModel` meant zero codec edits. That was right, but it only became a fact when
  `AdapterParityTest` ran green with two new cases — which is also what turned "both Jackson
  adapters handle the new records" from a belief into evidence.
- **Say which gate you could not run.** `-Pnative` needs GraalVM; this machine has Oracle JDK 26, so
  the one check that actually validates new reflect-config entries did not run locally. That belongs
  in the report as a deferral to CI, not as a silently skipped step.
