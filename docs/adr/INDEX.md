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
- **Consequences** — positive, negative, and neutral. Split under those three headings where the
  record is long enough to warrant it; a short one can run as a single list.
- **Proved by** — optional, and only where a behaviour is consumer-observable: the seam-crossing
  `*IntegrationTest` that demonstrates it (see [CONTRIBUTING — Testing](../CONTRIBUTING.md#testing)).
- **References** — related ADRs, docs, external sources

**No amendment sections before 1.0.0.** An ADR states the current decision as if decided today;
corrections are made in place and git history is the audit trail. A discovery that explains *why*
the design is what it is belongs in Context or Consequences. This inverts at 1.0.0, when a change
to an accepted ADR becomes a dated amendment or a superseding ADR — see
[ADR-019](019-pre-10-stability-policy.md).

**What an ADR is not.** It records a decision and its reasoning, not a copy of the code. Inline
lists of constants, operation names, module counts or accessor signatures drift the moment the code
moves and are the single biggest source of stale ADRs here — name the type or the test that holds
the list instead.

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
- [023 — Streaming TTS via Flow.Publisher&lt;byte[]&gt;](023-streaming-tts-via-flow-publisher.md)
- [028 — Qur'an and hadith validation: a ninth domain facade](028-sadiq-validation-facade.md)
- [031 — Deep research: a minutes-long, quota-scarce, stream-first operation on `client.sadiq()`](031-deep-research-facade.md)

### Transport and serialization

- [007 — JDK HttpClient as the default transport](007-jdk-httpclient-default-transport.md)
- [008 — JSON as an SPI with two Jackson adapters](008-json-spi-jackson-adapters.md)
- [017 — SSE parsing strategy](017-sse-parsing-strategy.md)

### SPIs and cross-cutting concerns

- [012 — Interceptor SPI](012-interceptor-spi.md)
- [013 — Observability SPI](013-observability-spi.md)
- [022 — Observability composition via `compose(...)` factory](022-observability-compose-factory.md)
- [014 — Retry policy defaults](014-retry-policy-defaults.md)
- [025 — Retry-After handling: ceiling, normalisation, and the quota hint](025-retry-after-handling.md)
- [026 — Rate-limit visibility: observation attributes and `RateLimitInfo` on the 429s](026-rate-limit-telemetry.md)
- [027 — `RetryPolicy`: a total sleep budget and a builder](027-retry-policy-builder-and-budget.md)

### Build, distribution, governance

- [009 — GraalVM native-image as a day-one CI target](009-native-image-day-one.md)
- [010 — Module layout](010-module-layout.md)
- [011 — Package conventions](011-package-conventions.md)
- [029 — Where published artifacts live, and what the coordinate has to be](029-publication-target.md)

### Framework adapters

- [020 — Spring Boot 4 starter shape](020-spring-boot-4-starter.md)
- [021 — Spring AI 2.0 adapter](021-spring-ai-2-adapter.md)
- [024 — Spring AI vendor options (FanarChatOptions family)](024-spring-ai-vendor-options.md)
- [030 — Google ADK Java adapter](030-google-adk-adapter.md)
