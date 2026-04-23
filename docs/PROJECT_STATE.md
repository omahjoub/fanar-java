# Project state

> **Snapshot — 2026-04-23.** Updated when the project crosses a milestone. If this file looks wrong or stale,
> that is the most important signal — update it in the same PR as whatever moved.

## Phase

**Design phase — closed.** All foundational decisions are captured in ADRs. Implementation has not started.

## What's decided

The shape of the SDK is fully captured in:

- [19 ADRs](adr/INDEX.md) — Java version, scope, API shape, SPIs, transport, JSON, retries, DTOs, package/module layout, stability policy, internals-aren't-a-contract.
- [Compatibility matrix](COMPATIBILITY.md) — the lighthouse: what Fanar offers, what core provides, what downstream adds.
- [API sketch](API_SKETCH.md) — aspirational code shape for every major call. Living document.
- [Library best practices](JAVA_LIBRARY_BEST_PRACTICES.md) — hygiene rules the implementation must respect.
- [Architecture](ARCHITECTURE.md) — Fanar API surface plus our own module layout and request-flow diagrams.

## What's built

- **Reactor skeleton** — 4 Maven modules (`core`, `json-jackson2`, `json-jackson3`, `bom`) with `module-info.java` and
  placeholder types. `./mvnw verify` passes with two expected warnings (module-name terminal digits, see
  ADR-001 / ADR-010).
- **CI** — build matrix (Java 21 and 25), link-check for every doc, dependency hygiene, zero published artifacts yet.
- **`.github/`** — PR template with scope-split checklist, issue templates, SECURITY, CODEOWNERS, dependabot
  (Maven + GitHub Actions). All consistent with the design.

**No Java implementation code yet.** The project is ready to start coding but has not started.

## What's next

In rough order of load-bearing importance:

1. **Chat domain DTOs** — records + sealed unions for `StreamEvent`, message roles, content parts. Smallest surface to validate the convention.
2. **`FanarClient` + builder + domain-facade interfaces** — the entry point callers touch first.
3. **Core SPIs** — `FanarJsonCodec`, `Interceptor`, `ObservabilityPlugin`, `ObservationHandle`. First public types under `.spi`.
4. **Transport + SSE parser** under `core.internal`. Wires the `HttpClient` and the chunk pipeline.
5. **Retry interceptor + `RetryPolicy`** with the defaults from ADR-014.
6. **Jackson 2 adapter** — `Jackson2FanarJsonCodec`, `ServiceLoader` descriptor, reachability metadata.
7. **Jackson 3 adapter** — same, against the new `tools.jackson.*` package family.
8. **GraalVM reachability metadata** per artifact + native-image smoke test in CI (ADR-009).

Each step is roughly one focused PR. The [API sketch](API_SKETCH.md) shows the target; the [ADRs](adr/INDEX.md) justify the choices.

## Cadence for updates

Update this file when:

- A phase completes (design → implementation → testing → release).
- A significant milestone ships (first DTO, first passing integration test, first tagged release, first framework adapter).
- An ADR gets superseded.

Commit the update in the same PR as the change that motivated it — not separately.
