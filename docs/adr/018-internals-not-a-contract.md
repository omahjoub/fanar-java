# ADR-018 — Internals are not a contract

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

Every implementation decision in this SDK (HTTP transport choice, SSE parsing approach, retry machinery,
observation-context propagation, connection pooling, JSON deserialization details, thread-pool wiring) is
provisional. In a Java 21 codebase targeting multiple frameworks over a multi-year lifespan, many of these choices
will need to change. The right to refactor them — freely, without a deprecation cycle — depends on a clear,
enforced separation between the public contract and the internal implementation.

Without explicit rules and mechanical enforcement, consumers end up depending on whatever they can see. Classes
marked `public` but "clearly not part of the API" become de-facto public surface. Refactoring becomes a breaking
change by accident. JLBP-2 and JLBP-10 speak to this implicitly; this ADR makes the rule load-bearing and explicit.

## Decision

Every Fanar-Java module has exactly two surfaces, and only one of them is a contract.

### Public contract

- The top-level package of the module (e.g., `qa.fanar.core`) — DTOs, facades, `FanarClient`, exceptions, enums.
- The `.spi` subpackage — extension interfaces (`Interceptor`, `FanarJsonCodec`, `ObservabilityPlugin`, and siblings).

Both are exported via `module-info.java`. Changes follow semver (ADR-019 for pre-1.0 rules). Deprecation cycles per
JLBP-7; API stability per JLBP-10.

### Internal implementation

- Everything under `qa.fanar.*.internal.*` (note the wildcard — every module has its own `.internal.*` subtree).
- **Not exported** via `module-info.java`.
- May be rewritten, replaced, or deleted in any release without deprecation. Not a contract.

### Enforcement

- `module-info.java` exports only the public packages. Internal types are literally unreachable from outside the
  module at compile time and runtime (with the standard JPMS reflection guards).
- No public API signature may reference an `internal.*` type. The module boundary enforces this mechanically: a
  compile error results if someone tries.
- Downstream modules (the two Jackson adapters today; future starters and adapters tomorrow; user application code
  always) reach `fanar-core` only through its public API and `.spi`.
- If a downstream module "reaches past" `.internal.*` (via JPMS command-line `--add-opens`, reflection hacks, or
  similar), breaking them in a refactor is not our fault. The module boundary made the rule; bypassing it is a
  downstream design error, and the resulting break is correct behavior.

### Adding new code

When adding code to `fanar-core`, the decision process is:

1. Does a user ever need to construct or reference this type directly? If no → `.internal.*`.
2. Is this type an interface that users may implement or invoke? If yes → top-level or `.spi`.
3. Default to `.internal.*`. Public is opt-in, not opt-out.

## Alternatives considered

- **Convention-only separation**: document that `.internal` packages are "not for external use" without actually
  restricting access via JPMS. *Rejected*: users depend on what they can see; the gap between documented intent and
  observed reality becomes a support burden.
- **Annotations only** (`@PublicApi`, `@InternalApi` markers). *Rejected*: annotations don't prevent use. They create
  an advisory warning at best; the module boundary creates an enforceable rule.
- **No separation** — publish everything, trust consumers to be careful. *Rejected*: every refactor becomes
  technically-breaking; library evolution stalls; semver becomes a fiction.

## Consequences

### Positive
- Every provisional implementation decision (all 16 prior ADRs) can be revisited without breaking downstream. We
  gain the freedom to evolve. Example: ADR-017 SSE parser can move from line-based to byte-level reactive, or to a
  third-party library, with zero downstream impact.
- Semver means what it says. "No breaking changes in a minor release" is enforceable at the module boundary, not a
  reviewer vigilance exercise.
- New contributors have a clear rule for placement: default-internal, justify-public. Faster code reviews, less
  bikeshedding.

### Negative / Trade-offs
- Requires discipline when adding code. "Should this be public or internal?" is a question for every new type.
  Mitigated by the default-to-internal rule and by the module boundary catching violations at compile time.
- Users with a legitimate need to reach an internal capability must either (a) request a public API for it or (b) use
  JPMS `--add-opens` at their own risk. Option (a) is a feature request; option (b) invalidates our support contract
  for that consumer.

### Neutral
- The rule is uniform across every module in the reactor (today: core, two Jackson adapters, BOM; tomorrow: more
  adapters, starters, extensions).

## References

- ADR-002 Narrow core SDK scope (what the public surface is *for*)
- ADR-003 Framework-agnostic public API (what the public surface is *not*)
- ADR-011 Package conventions (where `.internal.*` lives)
- ADR-019 Pre-1.0 stability policy (how the contract evolves)
- All other ADRs (they rest on this one)
- [`docs/JAVA_LIBRARY_BEST_PRACTICES.md`](../JAVA_LIBRARY_BEST_PRACTICES.md) § JLBP-2, § JLBP-10, § JLBP-19, § JLBP-20
