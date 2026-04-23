# ADR-019 — Pre-1.0 stability policy

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

The SDK is in active design. Early adopters need an explicit, unambiguous stability policy so they understand the
risk profile of depending on pre-release versions and so that we preserve design freedom until the first stable
release. JLBP-3 and JLBP-12 speak to this implicitly through semver and stability-communication requirements;
`SECURITY.md` references the versions supported for security fixes. This ADR codifies the contract.

Two failure modes to avoid:

1. **Accidental stability commitments**: users depending on 0.x APIs assume semver guarantees that don't yet apply,
   then feel betrayed when we evolve the API before 1.0.
2. **Unbounded churn**: no stability signal at all makes the SDK unusable even by risk-tolerant early adopters.

## Decision

### Pre-1.0 semantics (0.x.y releases)

- **Minor version bumps** (`0.x.0` → `0.(x+1).0`) may include **breaking changes** to the public API and `.spi`
  surface. Changes are documented in the changelog. No deprecation cycle is required.
- **Patch version bumps** (`0.x.y` → `0.x.(y+1)`) are **bug fixes only**. No API changes (additive or breaking).
- **Internals are always free to refactor**, regardless of version — per ADR-018.
- **Security fixes** ship for the **latest release only**. Users on older 0.x versions must upgrade to receive security
  patches.

### API freeze before 1.0.0

A **two-week API freeze** precedes the 1.0.0 cut:

- During the freeze window: no new public API added. Fixes, docs, tests only.
- The freeze is announced in the changelog and on the project's release notes.
- After the freeze window: cut 1.0.0. From that moment, strict semver applies.

### Post-1.0 (1.x.y and beyond)

- **Strict semver** per JLBP-10.
- **Breaking changes require a major version bump** and the deprecation workflow per JLBP-7: deprecate with
  `@Deprecated(since = "x.y.z", forRemoval = true)`, provide a migration path in Javadoc, keep the deprecated API
  working for at least one minor release before removal in the next major.
- **Sealed-interface variants** (the `StreamEvent` permits, exception hierarchy permits) are part of the API
  contract; adding a variant is a breaking change post-1.0 (JLBP-10).

## Alternatives considered

- **Strict semver from 0.1.0**. *Rejected*: locks the SDK into premature design decisions before user feedback has
  shaped the API. Effectively no freedom to correct mistakes between first release and 1.0.
- **No stability policy at all pre-1.0**. *Rejected*: users need some guarantees to adopt at all. A world with no
  pre-1.0 contract is a world with no pre-1.0 users.
- **Skip the API freeze window**. *Rejected*: the freeze is what makes 1.0.0 a meaningful milestone rather than
  just a version number on a changing codebase. Two weeks is a small cost for a credible stability commitment at
  release.

## Consequences

### Positive
- Early adopters make an informed adoption decision with clear expectations about the upgrade path.
- We retain the freedom to correct design mistakes (from the 19 ADRs and beyond) before the stability contract begins.
- 1.0.0 becomes a meaningful milestone: it's not just the next version number, it's the moment when the stability
  contract begins.
- Security-fix-latest-only is a well-established convention that minimizes maintenance burden during the pre-stable
  phase.

### Negative / Trade-offs
- Risk-averse enterprise consumers may defer adoption until 1.0 rather than adopt a 0.x version. Accepted — their
  caution is reasonable, and 1.0 is the audience-expansion moment.
- Minor-version churn may require users to re-test after each upgrade. Mitigated by clear changelog entries and the
  smallness of the API surface (ADR-002, ADR-016).

### Neutral
- Both `docs/JAVA_LIBRARY_BEST_PRACTICES.md` (JLBP-3 / JLBP-12) and `.github/SECURITY.md` reference this policy;
  updates to the policy must flow to both.

## References

- [`docs/JAVA_LIBRARY_BEST_PRACTICES.md`](../JAVA_LIBRARY_BEST_PRACTICES.md) § JLBP-3, § JLBP-7, § JLBP-10, § JLBP-12
- [`.github/SECURITY.md`](../../.github/SECURITY.md)
- ADR-018 Internals are not a contract
- Semantic Versioning 2.0.0 specification
