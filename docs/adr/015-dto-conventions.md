# ADR-015 — Hand-written DTO conventions

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

Fanar's OpenAPI spec describes ~80 schemas across 8 functional domains. We must decide how to model them in Java and
whether to generate them mechanically from the spec or hand-write them. The choice shapes every DTO in the SDK: their
naming, nullability story, polymorphism, builder ergonomics, and drift-resistance against future API changes.

## Decision

All DTOs are **hand-written**. No code generation from the OpenAPI spec; no annotation-processor plugins. The
following conventions apply across every domain package (ADR-011):

### Records for every DTO

DTOs are Java records, never classes, and never use inheritance. Records give us immutability, canonical
constructors, compact validation, native-image friendliness, and Jackson's constructor-based deserialization path
(lighter reflection surface).

### Nullable fields, not `Optional<T>`

Optional fields are modeled as nullable `T`, documented with `@Nullable` (JSpecify annotations, build-time only).
Effective Java (Item 55) and the Java Platform itself argue against `Optional` as a field type: records with
`Optional` have awkward serialization edge cases, and `Optional` was designed as a return-type contract.

Callers who want `Optional` semantics convert at the call site:

```java
Optional.ofNullable(response.someField())
```

### Sealed interfaces + record variants for polymorphic unions

Closed-set polymorphic types (Fanar has several: message roles, content parts, the SSE stream-event union) are modeled
as sealed interfaces with record variants. Pattern-matching switch (ADR-005) makes consumption exhaustive and
compiler-verified.

```java
public sealed interface Message permits SystemMessage, UserMessage, AssistantMessage, ThinkingMessage, ThinkingUserMessage {}
public record SystemMessage(String content, String name) implements Message {}
// ...
```

Jackson polymorphism (`@JsonTypeInfo`, discriminator mappings) lives in each Jackson adapter (ADR-008), **not on the
core DTOs**. Core records stay pure.

### Builders for request DTOs with many optional fields

For requests like `ChatRequest` — which has 30+ optional fields across sampling knobs, thinking controls, and
Islamic-RAG scope parameters — a raw record constructor is unusable. Each such DTO ships a static nested `Builder`
class with fluent `.withXxx()` methods and a `build()` validator:

```java
ChatRequest req = ChatRequest.builder()
    .model(ChatModel.FANAR_C_2_27B)
    .addMessage(SystemMessage.of("You are..."))
    .addMessage(UserMessage.of("Hello"))
    .temperature(0.7)
    .enableThinking(true)
    .build();
```

Builders are hand-written. No annotation processor, no dependency on Lombok or similar libraries (JLBP-1).

Response DTOs do not need builders — they are returned fully constructed from JSON deserialization.

### Minimal validation

- Required-field non-null enforced in the compact constructor and `Builder.build()`.
- Obvious range violations caught at construction: `temperature ∈ [0.0, 2.0]`, `n ≥ 1`, `maxTokens ≥ 1`.
- Fanar-controlled identifiers (`ChatModel`, `Source`, `FinishReason`, `ImageDetail`,
  `ContentFilterType`, `BookName`) are **open value-class records**, not closed enums:
  each exposes named constants for known wire values plus a permissive `of(String)` factory
  so callers are never blocked by SDK release cadence when the server adds a value. The
  IDE-discoverable catalogue lives in the public `KNOWN` set on each type. Strictly-SDK
  identifiers (`ErrorCode`, `JitterStrategy`) stay enums because their values map 1:1 to
  exception subtypes / library behaviour and adding a new one is an SDK release event.
- Semantic validation (model-specific constraints, Islamic-RAG rules, feature-gated flags) is Fanar's server's
  responsibility. We surface the server's rejection via the typed exception hierarchy (ADR-006).

### Drift detection

The `api-spec/openapi.json` file is checked into the repository. Any Fanar-side API change appears in the diff; PR
reviewers enforce that DTO updates accompany spec updates. This discipline is documented in `CONTRIBUTING.md` and
enforced by code review, not automation.

## Alternatives considered

- **OpenAPI Generator** (`openapi-generator-maven-plugin`). *Rejected*: loss of control over naming, Javadoc,
  builder shape, polymorphism handling. Record-mode exists but is fiddly; custom templates become the new maintenance
  burden.
- **Hybrid — generate records, hand-curate builders and facades**. *Rejected* after explicit user preference for
  full control. Also re-evaluable later if the hand-written toil proves excessive.
- **`Optional<T>` fields**. *Rejected*: against Effective Java guidance; awkward with JSON serialization; breaks the
  record/Optional combination.
- **`@NotNull` / `@Nullable` from a runtime annotation library** (e.g., javax.validation, `jakarta.annotation`).
  *Rejected*: runtime deps in core (JLBP-1). JSpecify is build-time only if we choose to add it later.

## Consequences

### Positive
- Full control over every aspect of the DTO surface: naming, Javadoc, builder ergonomics, exception messages.
- Javadoc quality is enterprise-grade because every type is curated, not generated.
- No dependency on generator tooling, its configuration quirks, or its release cadence.
- Native-image reachability metadata is hand-authored against the exact types we ship (ADR-009), no surprises from
  generated scaffolding.

### Negative / Trade-offs
- Upfront effort: ~80 record types × careful Javadoc × builder authoring = real work. Amortized over the project's
  lifetime but visible on the first implementation pass.
- Drift discipline depends on PR reviewers catching spec changes. The guardrail is the committed OpenAPI spec; if a
  reviewer misses a spec change, DTOs and spec can diverge silently.
- We cannot regenerate DTOs mechanically when Fanar publishes an API version bump — it's a manual code pass.

### Neutral
- The convention applies uniformly to every module (core and adapters, future and present).

## References

- ADR-002 Narrow core SDK scope
- ADR-003 Framework-agnostic public API
- ADR-005 Streaming via `Flow.Publisher` (sealed `StreamEvent`)
- ADR-008 JSON as an SPI with two Jackson adapters (polymorphism annotations live in adapters)
- ADR-011 Package conventions (DTO domain grouping)
- [`api-spec/openapi.json`](../../api-spec/openapi.json)
- Effective Java (Bloch), Item 55 — "Return optionals judiciously"
- JSpecify annotations project
