# ADR-011 — Package conventions

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

Java libraries partition public and private packages in different ways: some expose everything under a single root,
some use an explicit `.api` subpackage, some use annotation-driven visibility. The choice affects JPMS exports,
user mental model, IDE navigation, and the line between contract (stable) and implementation (refactorable).

Fanar's OpenAPI spec describes ~80 schemas across 8 functional domains (chat, audio, images, translations, poems,
moderation, tokens, models). Whatever package structure we adopt will host roughly that many DTO types plus the
facades, exception hierarchy, and SPI interfaces.

## Decision

Three conventions govern package placement across all modules:

### 1. Top-level package is the public API

The top-level package of a module is its public API. No `.api` subpackage. Example: `qa.fanar.core.FanarClient`,
`qa.fanar.core.chat.ChatRequest`. This matches the convention of most Java libraries (`java.net.http`,
`org.springframework.core`) and avoids the noise of an explicit `api`-suffix segment.

### 2. Extension interfaces live under `.spi`

User-implementable interfaces live in a `.spi` subpackage — `qa.fanar.core.spi.Interceptor`,
`qa.fanar.core.spi.FanarJsonCodec`, `qa.fanar.core.spi.ObservabilityPlugin`. This signals clearly that these types
are designed for extension and carries the same stability guarantees as the top-level API.

### 3. Implementation lives under `.internal.*`, not exported

Every class not part of the public contract lives under some `.internal.*` subpackage, and those packages are **not
exported** via `module-info.java`. Consumers (and even downstream modules) cannot depend on internal types; refactors
are safe (ADR-018).

### DTO grouping

DTOs and domain facades mirror the Fanar API's 8 domains:

```
qa.fanar.core
├── FanarClient                        // top-level client, entry point
├── FanarException & subtypes          // exception hierarchy
├── chat.*                             // ChatRequest / ChatResponse / sealed StreamEvent / thinking types
├── audio.*                            // speech / transcription / voices
├── images.*                           // image-generation types
├── translations.*
├── poems.*
├── moderation.*
├── tokens.*                           // tokenization request / response
├── models.*                           // model listing and metadata
├── spi.*                              // extension interfaces
└── internal.*                         // not exported
```

Each Jackson adapter module follows the same shape:

```
qa.fanar.json.jackson2.Jackson2FanarJsonCodec           (public)
qa.fanar.json.jackson2.internal.*                       (not exported)
```

### Artifact ↔ package alignment

Per JLBP-6, artifact IDs and package roots correspond 1:1: `fanar-core` ↔ `qa.fanar.core`;
`fanar-json-jackson2` ↔ `qa.fanar.json.jackson2`; `fanar-json-jackson3` ↔ `qa.fanar.json.jackson3`. Renaming an
artifact renames the package with it, never one without the other.

## Alternatives considered

- **Explicit `.api` subpackage** (`qa.fanar.core.api.FanarClient`). *Rejected*: adds noise in every import statement
  for no gain. The top-level package is where Java libraries conventionally publish their API.
- **Flat DTOs** (every DTO at `qa.fanar.core.*`). *Rejected*: ~80 types in one package becomes unscannable, and loses
  the domain grouping that users will want to navigate by.
- **Single `.model` subpackage** for all DTOs. *Rejected*: mixes unrelated domains (chat next to poems next to
  moderation); loses the 1:1 mapping with Fanar's API structure.

## Consequences

### Positive
- IDE navigation maps cleanly to the OpenAPI spec's domain structure.
- Adding a new Fanar domain is mechanical: new subpackage under the top-level root.
- The module boundary (ADR-018) enforces public-vs-internal separation mechanically; reviewers never have to argue
  about "should this be public?" — anything not under `.spi` or top-level simply cannot be reached from outside the
  module.
- Artifact/package alignment (JLBP-6) means the coordinate in a pom tells you exactly where to look in the source.

### Negative / Trade-offs
- 8 top-level subpackages on a single module is more than minimalists prefer. Traded for discoverability and
  scalability as Fanar adds domains.
- Sealed-interface variants (e.g., `StreamEvent` permits) live in the same subpackage — domain-grouped, not
  collected in a "union" subpackage. Minor aesthetic call.

### Neutral
- The `.internal.*` subtree is an anti-contract (ADR-018); it exists for organizational clarity, not external
  discoverability.

## References

- ADR-002 Narrow core SDK scope
- ADR-010 Module layout
- ADR-015 Hand-written DTO conventions
- ADR-018 Internals are not a contract
- [`docs/JAVA_LIBRARY_BEST_PRACTICES.md`](../JAVA_LIBRARY_BEST_PRACTICES.md) § JLBP-2, § JLBP-5, § JLBP-6, § JLBP-19, § JLBP-20
