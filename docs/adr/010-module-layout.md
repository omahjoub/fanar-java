# ADR-010 — Module layout

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

Decisions ADR-008 (JSON as an SPI with two Jackson adapters) and ADR-009 (native-image as a day-one CI target)
imply a multi-module build from day one. JLBP-15 additionally requires publishing a Bill-of-Materials (BOM) for
multi-module projects so consumers can import a single version coordinate. We must decide the reactor structure,
what gets published, and the directory layout.

## Decision

Modules divide into three kinds, and which kind a module is decides everything else about it —
whether it publishes, whether it carries the quality gates, whether it may take a dependency:

| Kind | Publishes | Examples |
|---|---|---|
| **Library** — the SDK a consumer depends on | yes, as a jar | `fanar-core`, the two JSON codecs, the three observability adapters, `fanar-interceptor-logging`, the two starters, `fanar-adk` |
| **BOM** — one version coordinate for all of the above | yes, as a pom | `fanar-java-bom` |
| **Support** — samples, live e2e, the native-image probe, the test fixture | never (`maven.deploy.skip`) | `spring-*-sample`, `e2e`, `e2e-graalvm`, `test-support` |

`bom/pom.xml` manages **every** library module, which is the BOM's whole job: a module that ships
but is missing from the BOM is a module a consumer must version by hand, which is exactly the
mixed-version problem the BOM exists to prevent. Adding a library module means adding a BOM entry in
the same change — and since that was reviewer memory once and failed for five months, it is now
enforced: `check-build` compares the BOM's managed set against the published set and fails on either
direction.

The artifacts are **not yet on Maven Central** — they ship as GitHub Release assets while the
Sonatype path is arranged (`docs/PROJECT_STATE.md`). Nothing about the layout changes when that
lands; the publication target does.

The **reactor parent POM is internal**: it exists at the repository root to orchestrate the reactor build but is
**never published to Maven Central**. Consumers import the BOM, not the reactor parent.

Repository layout is **flat**:

```
/core/              — fanar-core
/json-jackson2/     — fanar-json-jackson2
/json-jackson3/     — fanar-json-jackson3
/bom/               — fanar-java-bom
/pom.xml            — reactor parent (not published)
/docs/              — architecture docs, ADRs, best practices
/api-spec/          — Fanar OpenAPI spec (source of truth for DTOs, ADR-015)
/.github/           — CI, issue/PR templates
```

## Alternatives considered

- **Single module** now, split later. *Rejected*: ADR-008 requires two Jackson adapters from day one, so we're
  multi-module from the start regardless.
- **Nested directory layout** (`/modules/core/`, `/modules/json/jackson2/`). *Rejected*: four modules don't need
  hierarchy; flat paths are shorter in relative-path references (POM, IDE navigation, CI workflows). Nested layout
  can be introduced later — but doing so is a breaking refactor of every relative path (POMs, CI path filters,
  docs), so it is paid only when flatness has a cost someone can name, not at a module count: the reactor passed
  ten modules during 2026 without one, and ADR-030 adds another module flat.
- **Published reactor parent** (users could import it as a parent POM). *Rejected*: creates confusion about which
  artifact consumers should import. The BOM is the single user-facing multi-module coordinate.

## Consequences

### Positive
- The BOM is the canonical import for users (JLBP-15, JLBP-16): one version coordinate, all modules aligned.
- The reactor parent can be refactored freely without affecting consumers.
- Flat paths are short and unambiguous in every reference.
- Adding a future adapter (hypothetical Gson adapter, starter module, etc.) is mechanical: new top-level directory,
  new reactor entry, BOM update.

### Negative / Trade-offs
- Reorganizing to nested layout later is a breaking refactor for contributors' local builds and tooling. Deferred
  until flatness has a named cost (see Alternatives).
- Internal reactor parent is invisible to consumers — they can't import it as a Maven parent. Intentional; the BOM
  serves that role.

### Neutral
- **The library modules that can carry a `module-info.java` do**: core, both JSON codecs, the three
  observability adapters, the logging interceptor. The two Spring starters and `fanar-adk`
  deliberately do not — Spring's classpath scanning and `@AutoConfiguration` predate a clean JPMS
  story (ADR-020), and ADK's stack declares no JPMS modules (ADR-030) — and instead declare an
  explicit `Automatic-Module-Name`, because without one JPMS derives the name from the filename:
  `fanar-spring-boot-4-starter` derives to an invalid one, `fanar-adk` to one off the package root.
  Support modules need neither.
- The BOM ships no classes and no descriptor; it is pom-packaging only.

## References

- ADR-008 JSON as an SPI with two Jackson adapters
- ADR-009 GraalVM native-image as a day-one CI target
- ADR-011 Package conventions
- ADR-018 Internals are not a contract
- [`docs/JAVA_LIBRARY_BEST_PRACTICES.md`](../JAVA_LIBRARY_BEST_PRACTICES.md) § JLBP-5, § JLBP-15, § JLBP-19, § JLBP-20
