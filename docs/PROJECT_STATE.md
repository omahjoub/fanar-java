# Project state

> **Snapshot — 2026-04-23.** Updated when the project crosses a milestone. If this file looks wrong or stale,
> that is the most important signal — update it in the same PR as whatever moved.

## Phase

**Implementation phase — started.** All foundational decisions are captured in ADRs; the first contract-layer
types are on disk (exception hierarchy), quality gates are live on `fanar-core`. Subsequent PRs add the SPIs,
domain DTOs, `FanarClient` facade, and transport internals — in that order.

## What's decided

The shape of the SDK is fully captured in:

- [19 ADRs](adr/INDEX.md) — Java version, scope, API shape, SPIs, transport, JSON, retries, DTOs, package/module layout, stability policy, internals-aren't-a-contract.
- [Compatibility matrix](COMPATIBILITY.md) — the lighthouse: what Fanar offers, what core provides, what downstream adds.
- [API sketch](API_SKETCH.md) — aspirational code shape for every major call. Living document.
- [Library best practices](JAVA_LIBRARY_BEST_PRACTICES.md) — hygiene rules the implementation must respect.
- [Architecture](ARCHITECTURE.md) — Fanar API surface plus our own module layout and request-flow diagrams.

- **Reactor skeleton** — 4 Maven modules (`core`, `json-jackson2`, `json-jackson3`, `bom`) with `module-info.java`.
  `./mvnw verify` passes with two expected warnings (module-name terminal digits, see ADR-001 / ADR-010).
- **Core contract — exception hierarchy** — `qa.fanar.core.FanarException` sealed hierarchy with 13 concrete
  subtypes (one per `ErrorCode`), plus `ErrorCode` and `ContentFilterType` enums. 55 tests, 100 % JaCoCo coverage
  across 19 classes. See ADR-006.
- **Quality gates on `fanar-core`** — JaCoCo `check` enforces 100 % on instruction / line / branch / method /
  complexity; `dependency:analyze` fails on undeclared or unused direct deps; Javadoc doclint runs at javac time.
  Adapter modules stay in skeleton mode (`jacoco.skip=true`) until they carry real code.
- **Library-first dependency hygiene** — the reactor parent no longer imports Spring Boot's BOM; versions come
  from `junit-bom` (tests) and explicit pins. No implicit transitives from framework BOMs.
- **CI** — build matrix (Java 21 and 25), link-check for every doc, dependency hygiene gates via `mvn verify`,
  zero published artifacts yet.
- **`.github/`** — PR template with scope-split checklist, issue templates, SECURITY, CODEOWNERS, dependabot
  (Maven + GitHub Actions). All consistent with the design.

No `FanarClient`, no SPIs, no DTOs, no transport code yet — those are the next PRs below.

## What's next

In the order we plan to tackle them — each one its own focused PR:

1. **Core SPIs** — interfaces only: `FanarJsonCodec`, `Interceptor` + `Chain`, `ObservabilityPlugin` +
   `ObservationHandle` + `FanarObservationAttributes`. First public types under `.spi`. Depends on the exception
   hierarchy (Chain propagates `FanarException`).
2. **`RetryPolicy` record + `JitterStrategy`** — pure-data public type and its factories (`defaults()`,
   `disabled()`, `with*` methods). No retry logic yet — just the config surface.
3. **Chat domain DTOs** — records + sealed unions for `StreamEvent`, message roles, content parts. Validates
   record + sealed + builder conventions across a real domain.
4. **`FanarClient` + builder + domain-facade interfaces** — the entry point callers touch first.
   Implementation-free first pass; every method throws `UnsupportedOperationException` until transport lands.
5. **Transport + SSE parser** under `core.internal`. Wires the `HttpClient`, the SSE pipeline, and the
   `FanarClient` methods to real behaviour.
6. **Retry + bearer-token interceptors** — concrete implementations of the SPI, living under `core.internal`.
7. **Jackson 3 adapter** — `Jackson3FanarJsonCodec`, `ServiceLoader` descriptor, reachability metadata.
8. **Jackson 2 adapter** — mirror of the Jackson 3 adapter against the `com.fasterxml.jackson.*` package family.
9. **GraalVM reachability metadata + native-image smoke test** in CI (ADR-009).

The [API sketch](API_SKETCH.md) shows the target; the [ADRs](adr/INDEX.md) justify the choices.

## Cadence for updates

Update this file when:

- A phase completes (design → implementation → testing → release).
- A significant milestone ships (first DTO, first passing integration test, first tagged release, first framework adapter).
- An ADR gets superseded.

Commit the update in the same PR as the change that motivated it — not separately.
