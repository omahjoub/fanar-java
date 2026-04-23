# Architecture Decision Records

A log of architectural decisions for the Fanar Java SDK. Each record captures the context that made the decision
necessary, the alternatives considered and why we rejected them, the consequences we expect, and references to
related records. Don't deviate from an ADR without superseding it and recording the reason.

## Format

Every ADR follows an extended Michael Nygard template:

- **Status** — `Accepted` / `Proposed` / `Superseded by ADR-NNN` / `Deprecated`
- **Date** and **Deciders**
- **Context** — the forces at play, what made this decision necessary
- **Decision** — the choice, stated declaratively
- **Alternatives considered** — options we rejected, with reasoning
- **Consequences** — positive, negative, and neutral
- **References** — related ADRs, docs, external sources

## Index

### Foundational

- [001 — Java 21 as the minimum supported version](001-java-21-minimum-version.md)
- [002 — Narrow core SDK scope](002-core-sdk-scope.md)
- [003 — Framework-agnostic public API](003-framework-agnostic-api.md)
- [018 — Internals are not a contract](018-internals-not-a-contract.md)
- [019 — Pre-1.0 stability policy](019-pre-10-stability-policy.md)

### API shape

- [004 — Sync-primary API with async sugar](004-sync-primary-async-sugar.md)
- [005 — Streaming via Flow.Publisher](005-streaming-via-flow-publisher.md)
- [006 — Unchecked exception hierarchy](006-unchecked-exception-hierarchy.md)
- [015 — Hand-written DTO conventions](015-dto-conventions.md)
- [016 — FanarClient builder and domain facades](016-fanarclient-builder-domain-facades.md)

### Transport and serialization

- [007 — JDK HttpClient as the default transport](007-jdk-httpclient-default-transport.md)
- [008 — JSON as an SPI with two Jackson adapters](008-json-spi-jackson-adapters.md)
- [017 — SSE parsing strategy](017-sse-parsing-strategy.md)

### SPIs and cross-cutting concerns

- [012 — Interceptor SPI](012-interceptor-spi.md)
- [013 — Observability SPI](013-observability-spi.md)
- [014 — Retry policy defaults](014-retry-policy-defaults.md)

### Build, distribution, governance

- [009 — GraalVM native-image as a day-one CI target](009-native-image-day-one.md)
- [010 — Module layout](010-module-layout.md)
- [011 — Package conventions](011-package-conventions.md)
