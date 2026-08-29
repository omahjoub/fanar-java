# Contributing

Thanks for considering a contribution. This guide covers what to read first, how to set up locally, and how to
propose changes that land cleanly.

## First-time reading order

1. **[README](../README.md)** — what we're building and why.
2. **[Project state](PROJECT_STATE.md)** — what phase we're in and what's next on the roadmap.
3. **[Compatibility matrix](COMPATIBILITY.md)** — the lighthouse: what's core, what's framework-layer, what's Fanar-exclusive. Every scope question traces back here.
4. **[ADRs](adr/INDEX.md)** — every non-obvious decision, grouped into five categories. Don't deviate from an ADR without superseding it.
5. **[API sketch](API_SKETCH.md)** — the target code shape. Living document.
6. **[Architecture](ARCHITECTURE.md)** — module layout, request-flow diagrams, "where does X live?".
7. **[Library best practices](JAVA_LIBRARY_BEST_PRACTICES.md)** — hygiene every PR must respect.
8. **[Glossary](GLOSSARY.md)** — Fanar-specific and project-specific terminology.

If you read those in order, you have the full context. Expect ~30 minutes.

## Local setup

Prerequisites: JDK 21 or later on `PATH`. Nothing else.

```bash
git clone <your fork>
cd fanar-java
./mvnw verify
```

`verify` passes with two expected warnings about module-name terminal digits (documented in ADR-010).
If it fails for any other reason, that is a bug — please open an issue.

### Running one module only

```bash
./mvnw -pl core -am verify                 # core + the test-support fixture its integration tests use
./mvnw -pl json-jackson3 -am verify        # adapter + its dependencies
./mvnw -pl spring-ai-starter -am verify    # Spring AI adapter + starter chain
./mvnw -pl core -am test -Dtest=ChatRequestTest -Dsurefire.failIfNoSpecifiedTests=false   # one test class
./mvnw verify -Dgroups=integration         # only the seam-crossing tests (see Testing below)
```

`-am` builds the reactor siblings a module depends on — since 0.4.0 that includes the unpublished `test-support`
fixture for every module with `*IntegrationTest` classes. With `-Dtest=…`, `-Dsurefire.failIfNoSpecifiedTests=false`
keeps Surefire from failing in the sibling modules where the pattern matches nothing.

### Running the live e2e suite

The `fanar-java-e2e` module is gated on `FANAR_API_KEY` — without it, the live tests skip silently.
With a real key in scope:

```bash
FANAR_API_KEY=… ./mvnw -pl e2e -am verify
```

PR CI does not run live tests (no key in PR scope); the nightly job will (planned).

## Getting a change in

1. **Discuss first for substantial changes.** Open an issue or comment on an existing one. Use the appropriate issue
   template — particularly the *Scope* dropdown on feature requests, which forces the core-vs-framework-layer
   conversation up front.
2. **Fork → branch from `main` → push → PR.**
3. **Fill the PR template.** The scope-split checklists are not decorative. Reviewers will ask about unchecked items.
4. **Keep PRs focused.** One design decision per PR. One bug fix per PR. Large multi-concern PRs get broken up in
   review — saving both sides time.

## Proposing a design change

If your change touches the public API, adds or alters an SPI, changes scope, or affects stability, it needs an ADR.

1. Pick the next unused number — the highest existing number plus one, regardless of which [INDEX](adr/INDEX.md)
   section it sits in (`ls docs/adr | sort | tail -1`). Numbers are assigned in creation order and **never**
   renumbered.
2. Copy an existing ADR as a template (they all follow the extended Michael Nygard format):
   ```
   cp docs/adr/019-pre-10-stability-policy.md docs/adr/020-my-decision.md
   ```
3. Fill in the sections: Status (`Proposed` initially), Date, Deciders, Context, Decision, Alternatives considered,
   Consequences, References.
4. Open a PR containing the ADR, the code change it motivates (if applicable), and the seam-crossing
   `*IntegrationTest` that proves the behaviour the ADR promises — and name that test in the ADR (see
   [Testing](#testing)).
5. When the PR merges, flip the ADR's Status to `Accepted`.
6. If the change supersedes an existing ADR, set the old one's Status to `Superseded by ADR-XYZ` and add a cross-
   reference both ways.

## Commit messages

Short, imperative, module-prefixed:

```
core: add ChatRequest and ChatResponse records
json-jackson3: wire ServiceLoader descriptor
docs: update ADR-008 with Central Portal note
ci: bump Java matrix to 21 and 25
```

Reference ADRs where relevant: `core: implement retry chain (ADR-012, ADR-014)`.

## Coding conventions

The full set lives in [Library best practices](JAVA_LIBRARY_BEST_PRACTICES.md). Highlights:

- **Core module has zero runtime dependencies.** Any new dep is an ADR conversation, not a PR.
- **No third-party types on the public API surface.** JDK types (`Flow.Publisher`, `CompletableFuture`, etc.) and
  our own DTOs only.
- **Top-level package = public API**, `.spi` = extension interfaces, `.internal` = implementation (not exported).
- **Records** for DTOs, **sealed interfaces** for unions, no `Optional` fields. See ADR-015.
- **Javadoc** on every public type and method. `-Xdoclint:all,-missing` is enforced at compile time.
- **`module-info.java`** exports only public packages. Never internal ones.
- **`-parameters` is enabled globally.** Spring MVC's `@PathVariable String foo` binds by parameter name
  reflectively; the flag must be on for that to work without explicit name args.
- **Tests** follow the layers and rules in [Testing](#testing) below.

## Testing

Three layers, told apart by name and tag. All of them run under Surefire in the `test` phase (hermetic and fast —
no Failsafe), and every layer can be selected with `-Dgroups=` / `-DexcludedGroups=`:

| Layer | Name | Tag | Proves |
|---|---|---|---|
| Unit | `*Test` | — | one class, in process — SPI lambdas and hand-rolled recorders instead of mocks (Mockito stays out) |
| Seam-crossing | `*IntegrationTest`, in the module's public package | `integration` | a behaviour a consumer can observe, through the public `FanarClient.builder()` (or the starter's `ApplicationContextRunner`), the real interceptor chain and JDK transport, against a scripted local server |
| Live | `Live*Test` in `e2e` | `live` | observed Fanar wire behaviour; gated on `FANAR_API_KEY`, skips silently without it |

**The seam-crossing rule.** Every behaviour an ADR promises to a consumer has an `*IntegrationTest` that proves it
end to end, and the ADR names that test. JaCoCo measures execution, not integration: the retry loop had 100 %
coverage and sixteen green unit tests for two releases while no HTTP-status error ever reached it (ADR-025). A unit
test that hands the unit the outcome it expects proves the unit, not the wiring.

The fixture is the unpublished `test-support` module (`fanar-java-test-support`; JDK-only, so `core` uses it too).
`ScriptedHttpServer` answers from a queue of `Reply`s on a loopback port, records every request and — declared as an
`@AutoClose` field — fails the test if a scripted reply was never requested or an unscripted request arrived.
`CollectingSubscriber<T>` drains a `Flow.Publisher` and waits, with a timeout, for items or the terminal signal.
`FanarClientRetryIntegrationTest` (core) is the pattern to copy; `FanarAutoConfigurationRetryIntegrationTest` shows
the same seam entered through a Spring context.

Rules for every test:

- **No `Thread.sleep`.** Wait on latches and futures with a timeout and assert the result
  (`assertTrue(latch.await(…))`); wall-clock assertions only as generous lower bounds where the behaviour *is* a
  sleep. A 60 s JUnit timeout (root `pom.xml`, `disabled_on_debug`; 5 min in `e2e`) turns a hang into a failure
  with a stack trace — add `@Timeout` only where tighter matters.
- **Assertions**: JUnit `Assertions` (with messages) outside the Spring modules, AssertJ inside them;
  `assertDoesNotThrow` says "must not throw" explicitly.
- **Scripted-server tests assert the hit count** — retries are counted, never assumed.
- **Shape**: flat classes with behaviour-named methods; `@Nested` only to group facades inside an integration
  class; explicit imports.
- **Spring**: `ApplicationContextRunner` + `FilteredClassLoader` + `@Configuration(proxyBeanMethods = false)` for
  auto-config tests; a real server, never a mock, for wire-format tests (`FanarChatModelTest`,
  `FanarHealthIndicatorTest`).
- **Dependencies**: test scope stays JDK + JUnit + the fixture (the core-only modules also get the Jackson 3 codec,
  discovered through `ServiceLoader`, never imported); `dependency:analyze` fails on unused or undeclared ones.
- **Live tests fail loudly.** The only tolerated exceptions are documented nondeterministic outcomes and
  spec-documented gating, each with a dated note.

## Quality gates

Every shipping module enforces:

- **JaCoCo 100 %** on instructions, lines, branches, methods, complexity. Sample apps and `e2e*` modules
  set `jacoco.skip=true`.
- **`dependency:analyze` strict** — fails on undeclared or unused direct deps. Sample apps disable it.
- **Doclint** at javac time.

When CI flakes on a coverage gate, the failing job uploads the JaCoCo HTML report as an artifact named
`jacoco-java-{21,25}` — drill into the package row at < 100 % and the highlighted source line tells you
which branch is missed. Concurrency-flake fixes belong on the test (deterministic ordering, latches),
not on the threshold.

## Releasing a new version

We use the **release-and-bump** flow (Pattern B): every tagged commit's `pom.xml` matches the
release version exactly — no `-SNAPSHOT` suffix appears in published artifacts — and `main` is
bumped back to the next snapshot immediately after tagging. The `release.yml` workflow enforces
the version invariant by failing fast if the pom doesn't match the resolved version, and only a
tag push creates a real release (`workflow_dispatch` is for dry runs).

The full replayable checklist — preflight, changelog finalization, release branch, dry run,
tag, bump-back, troubleshooting, and the release-notes template — lives in
**[docs/RELEASING.md](RELEASING.md)**. Follow it verbatim; when a release surprises you, fix
the runbook in the same PR as the fix.

## Where to ask questions

- **[GitHub Discussions](https://github.com/omahjoub/fanar-java/discussions)** — questions, ideas, help.
- **Issue tracker** — bugs and concrete feature requests only.
- **[SECURITY.md](../.github/SECURITY.md)** — private vulnerability reporting. Never open a public issue for a vuln.

## The spirit of this repo

- **Strong narrow core; adaptable edges.** Most of our decisions aim at a small, evolvable, framework-agnostic core.
- **Internals are not a contract.** Anything under `.internal.*` can change freely. Only the public API and `.spi`
  carry stability guarantees (see ADR-018).
- **Every decision is reasoned, not decreed.** If an ADR doesn't explain *why*, that's a bug — please open a PR to
  strengthen it.
