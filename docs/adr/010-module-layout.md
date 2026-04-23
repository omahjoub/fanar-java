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

Four artifacts are **published** to Maven Central:

| Artifact | Packaging | Purpose |
|---|---|---|
| `qa.fanar:fanar-core` | jar | Typed API, SPIs, DTO reachability metadata |
| `qa.fanar:fanar-json-jackson2` | jar | Jackson 2 adapter, `provided` scope on Jackson (ADR-008) |
| `qa.fanar:fanar-json-jackson3` | jar | Jackson 3 adapter, `provided` scope on Jackson (ADR-008) |
| `qa.fanar:fanar-java-bom` | pom | Bill-of-Materials pinning the three module versions |

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
  can be introduced later once the module count justifies it — but doing so is a breaking refactor, so we only pay
  it once the count passes ~10.
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
- Reorganizing to nested layout later (if the repo grows past ~10 modules) is a breaking refactor for contributors'
  local builds and tooling. Rare event; deferred cost.
- Internal reactor parent is invisible to consumers — they can't import it as a Maven parent. Intentional; the BOM
  serves that role.

### Neutral
- Every module ships a `module-info.java` (JLBP-20, ADR-011).
- The BOM module ships no classes and no `module-info.java`; it's pom-packaging only.

## References

- ADR-008 JSON as an SPI with two Jackson adapters
- ADR-009 GraalVM native-image as a day-one CI target
- ADR-011 Package conventions
- ADR-018 Internals are not a contract
- [`docs/JAVA_LIBRARY_BEST_PRACTICES.md`](../JAVA_LIBRARY_BEST_PRACTICES.md) § JLBP-5, § JLBP-15, § JLBP-19, § JLBP-20
