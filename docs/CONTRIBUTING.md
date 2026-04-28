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
./mvnw -pl core verify                # core only
./mvnw -pl json-jackson3 -am verify   # adapter + its dependencies
./mvnw -pl spring-ai-starter verify   # Spring AI adapter + starter chain
```

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

1. Pick the next unused number (look at the [INDEX](adr/INDEX.md) — last entry is highest). Numbers are assigned in
   creation order and **never** renumbered.
2. Copy an existing ADR as a template (they all follow the extended Michael Nygard format):
   ```
   cp docs/adr/019-pre-10-stability-policy.md docs/adr/020-my-decision.md
   ```
3. Fill in the sections: Status (`Proposed` initially), Date, Deciders, Context, Decision, Alternatives considered,
   Consequences, References.
4. Open a PR containing the ADR and (if applicable) the code change it motivates.
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
- **Test patterns to mimic**: `ApplicationContextRunner` + AssertJ for auto-config tests;
  `FilteredClassLoader(ChatModel.class)` to assert "without spring-ai on classpath, the bean isn't
  registered"; real `HttpServer` (no mocks) for adapter wire-format tests — see `FanarChatModelTest` and
  `FanarHealthIndicatorTest`.

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

We use the **release-and-bump** flow: every tagged commit's `pom.xml` matches the release version
exactly — no `-SNAPSHOT` suffix appears in published artifacts. The release workflow guards this
by failing fast if the pom doesn't match the resolved version.

Step by step:

1. **Cut a release branch** from `main`:
   ```bash
   git switch -c release/0.1.0 main
   ./mvnw -B versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
   git commit -am "release: 0.1.0"
   git push -u origin release/0.1.0
   ```

2. **Dry-run the release workflow** from the release branch:
   `Actions → Release → Run workflow → Branch: release/0.1.0, version: 0.1.0, dry_run: true`.
   The workflow builds, verifies, stages 12 artifacts (9 library jars + BOM `.pom` + 2 sample fat
   jars), uploads them to the workflow run page, and stops short of creating a GitHub Release.
   Inspect the artifact list — every filename should end in `0.1.0.jar` / `0.1.0.pom`.

3. **Open a PR** `release/0.1.0 → main`. Review checks the version bump and changelog.

4. **Merge** — squash or merge-commit, doesn't matter. The release branch only has one commit
   (the version bump), so both produce equivalent history on main.

5. **Tag the merged commit**:
   ```bash
   git switch main && git pull
   git tag -a v0.1.0 -m "Release 0.1.0"
   git push origin v0.1.0
   ```
   Tag-push fires the release workflow again, this time creating the GitHub Release with the
   12 artifacts attached and auto-generated PR notes.

6. **Bump main back to the next snapshot** — mandatory follow-up:
   ```bash
   git switch -c bump/0.2.0-SNAPSHOT main
   ./mvnw -B versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
   git commit -am "build: bump to 0.2.0-SNAPSHOT"
   ```
   PR → main. Without this, every subsequent dev build still says `0.1.0` and trying to release
   `0.1.0` again will fail noisily.

The `Verify pom version matches release version` step in `release.yml` enforces step 1 — if you
forget the `versions:set` commit, the workflow fails with a one-line fix instruction.

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
