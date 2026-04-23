# ADR-008 — JSON as an SPI with two Jackson adapters

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

JSON serialization is central to the SDK: every request body, every response payload, and every SSE chunk payload
passes through a JSON codec. We must pick an approach that balances four constraints:

1. Core must have **zero runtime dependencies** (JLBP-1, ADR-002).
2. The user's Spring Boot version determines their Jackson major: Spring Boot 3.x ships Jackson 2 under
   `com.fasterxml.jackson.*`; Spring Boot 4.x ships Jackson 3 under `tools.jackson.*`. These are different libraries
   and cannot be unified by a single dependency.
3. We must not reinvent JSON parsing — we will never match the maturity of Jackson, Gson, or Moshi.
4. The core must remain framework-agnostic (ADR-003).

Picking a direct Jackson dependency forces us to choose Jackson 2 *or* Jackson 3, splitting our user base. Picking
neither forces us to hand-roll JSON or pick a smaller library (Gson / Moshi) that has its own version politics.

## Decision

JSON is **an SPI**, defined in `qa.fanar.core.spi.FanarJsonCodec` with two methods (decode from `InputStream`, encode
to `OutputStream`). The core module has zero JSON dependencies. Codec discovery uses `ServiceLoader`; users can also
supply a codec explicitly via the builder.

Two adapter modules ship from day one:

| Module | Jackson version | Target ecosystem | Declared scope |
|---|---|---|---|
| `fanar-json-jackson2` | `com.fasterxml.jackson:jackson-databind` 2.x | Spring Boot 3.x / Jackson 2 users | `provided` |
| `fanar-json-jackson3` | `tools.jackson:jackson-databind` 3.x | Spring Boot 4.x / Jackson 3 users | `provided` |

`provided` scope means the user's Spring Boot version supplies the concrete Jackson runtime; we compile against the
API without forcing a version. The adapter constructor accepts a user-configured `ObjectMapper`, so Spring-managed
customizations (mixins, modules, custom deserializers) flow through automatically.

Each adapter ships its own GraalVM reachability metadata (ADR-009).

No Gson, Moshi, JSON-B, or other JSON adapters ship at launch. Adding them later is a non-breaking additive change.

## Alternatives considered

- **Hand-rolled JSON codec** in core. *Rejected*: we would never match specialist-library maturity; every schema
  change becomes our maintenance burden; edge cases (UTF-8, numeric precision, polymorphism) are a minefield.
- **Direct Jackson dep in core** (pick 2 or 3). *Rejected*: splits the user base, violates zero-deps, forces
  consumers on the other Jackson major into a dual-Jackson classpath where customizations don't apply.
- **Jackson dep in core, shaded**. *Rejected*: violates JLBP-18 (no shading); prevents the user's Jackson
  customizations from applying; native-image hostile.
- **Ship a Gson / Moshi adapter alongside Jackson at launch**. *Rejected* — for now, deferred. No demand signal yet.
  The SPI makes them additive when demand emerges.

## Consequences

### Positive
- Core stays zero-deps (JLBP-1, ADR-002).
- Both Spring Boot ecosystems have a first-class, native adapter.
- Users' `ObjectMapper` customizations apply — no fighting with auto-configuration.
- Native-image story is better on Jackson 3 (ADR-009) but works on both.
- Future adapters (Gson, Moshi, JSON-B, GraalVM code-gen codec) are additive: new artifact, no change to core or
  existing adapters.

### Negative / Trade-offs
- Two adapter modules to maintain. Near-identical code, different import paths. Real but bounded cost.
- Users must add one adapter artifact to their pom. The helpful `build()` error message (ADR-016) points to the
  right artifact by Spring Boot major.

### Neutral
- Polymorphism annotations (`@JsonTypeInfo`, custom deserializers) live in each adapter, not in core DTOs. Core DTOs
  stay pure records.

## References

- ADR-002 Narrow core SDK scope
- ADR-003 Framework-agnostic public API
- ADR-009 GraalVM native-image as a day-one CI target
- ADR-015 Hand-written DTO conventions
- ADR-016 `FanarClient` builder and domain facades
- [`docs/JAVA_LIBRARY_BEST_PRACTICES.md`](../JAVA_LIBRARY_BEST_PRACTICES.md) § JLBP-1, § JLBP-18
