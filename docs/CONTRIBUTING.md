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

The build is currently a reactor skeleton — `verify` passes with two expected warnings about module-name terminal
digits (documented in ADR-010). If it fails for any other reason, that is a bug; please open an issue.

### Running one module only

```bash
./mvnw -pl core verify              # core only
./mvnw -pl json-jackson3 -am verify # adapter + its dependencies
```

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

- Core module has **zero runtime dependencies**. Any new dep is an ADR conversation, not a PR.
- **No third-party types on the public API surface.** JDK types (`Flow.Publisher`, `CompletableFuture`, etc.) and
  our own DTOs only.
- **Top-level package = public API**, `.spi` = extension interfaces, `.internal` = implementation (not exported).
- **Records** for DTOs, **sealed interfaces** for unions, no `Optional` fields. See ADR-015.
- **Javadoc** on every public type and method. `-Xdoclint:all,-missing` is enforced at compile time.
- **`module-info.java`** exports only public packages. Never internal ones.

## Running tests

```bash
./mvnw verify                  # full reactor
./mvnw -pl core verify         # one module
```

Skeleton modules currently have `<jacoco.skip>true</jacoco.skip>` and `dependency:analyze` unbound. When your PR
adds real code and tests to a module, remove those overrides in the same PR.

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
