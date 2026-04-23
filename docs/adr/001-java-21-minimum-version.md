# ADR-001 — Java 21 as the minimum supported version

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

The Fanar Java SDK must declare a minimum Java version. This decision is load-bearing: it determines which language
features are available on the public API (records, sealed interfaces, pattern-matching switch), which JDK APIs we can
rely on (`java.net.http.HttpClient`, `java.util.concurrent.Flow`, virtual threads, structured concurrency), and which
consumer ecosystems we can reach. Lowering the floor later is safe; introducing use of a newer API within a released
version is not. The call is effectively irreversible for the lifetime of a major version.

Our target ecosystems have heterogeneous Java floors: Spring Boot 3.x / Spring AI 1.x / LangChain4j 1.x / Quarkus 3.x
anchor at Java 17; Spring AI 2.x and Spring Boot 4.x anchor at Java 21+. Recent state-of-the-ecosystem surveys show
Java 21 adoption growing but Java 17 still representing the plurality of production workloads.

## Decision

`fanar-core` targets **Java 21 LTS** (`maven.compiler.release=21`). Downstream adapter or starter modules may target
higher when their ecosystem requires it (for instance a Spring Boot 4 starter pinned at Java 25) but never lower than
core.

## Alternatives considered

- **Java 17** — would maximize reach across the enterprise ecosystem, still provides records, sealed interfaces, JDK
  `HttpClient`, `Flow.Publisher`, JPMS, and pattern matching. *Rejected* because it excludes virtual threads, which
  underpin the sync-primary concurrency model (ADR-004). Without virtual threads, we would be designing against the
  old sync-vs-async tradeoff for a 10-year library lifespan.
- **Java 25** (current LTS at the time of writing) — gives stable structured concurrency, scoped values, and newer
  language features. *Rejected* because it locks out consumers not on the latest LTS — a significant and, in enterprise
  contexts, slow-moving audience.
- **Java 11** — would reach the widest classic base. *Rejected* immediately: no records, no sealed types, no virtual
  threads. Building a modern SDK with Java 11 idioms means giving up the language features that define our DTO and
  streaming design.

## Consequences

### Positive
- Sync calls run on virtual threads without blocking carrier threads, scaling like async without caller ceremony
  (ADR-004).
- Records, sealed interfaces, and pattern-matching switch are idiomatic in the public API.
- `HttpClient` is `AutoCloseable` from Java 21, simplifying client lifecycle (ADR-016).
- GraalVM 24+ supports virtual threads in native-image, preserving the native-image story (ADR-009).

### Negative / Trade-offs
- Consumers running on a Java-runtime version below 21 cannot use `fanar-core` directly until they upgrade the JVM
  underneath their application. This is not a compile-level block (Spring Boot 3 apps compile fine) but a runtime
  block.
- A share of production workloads currently on Java 11 and earlier are out of scope.

### Neutral
- The CI test matrix covers the declared minimum and the current LTS: `[ '21', '25' ]`. Both must stay green; the
  matrix updates when a new LTS arrives.

## References

- [`docs/JAVA_LIBRARY_BEST_PRACTICES.md`](../JAVA_LIBRARY_BEST_PRACTICES.md) § minimum Java version
- [`pom.xml`](../../pom.xml) — `java.version` property
- [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) — Java matrix
- JEP 444 — Virtual Threads (finalized in Java 21)
- ADR-004 Sync-primary API with async sugar
