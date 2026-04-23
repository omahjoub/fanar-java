# ADR-002 — Narrow core SDK scope

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

A Java SDK for an LLM provider sits in a crowded neighborhood. Adjacent frameworks (Spring AI, LangChain4j, Quarkus
LangChain4j, and others) already ship opinionated solutions for conversation memory, prompt templating, vector-store
integration, structured-output synthesis, evaluation harnesses, and agentic orchestration. A new SDK that bundles all
of these duplicates their work, dates quickly, and — critically — fights rather than complements the frameworks our
users have already chosen.

We must decide the boundary between what belongs inside `fanar-core` and what belongs in downstream modules (or in
existing frameworks). The boundary decision shapes every later API choice.

## Decision

`fanar-core` is a **thin, typed, pluggable transport** over the Fanar API. Its responsibilities are bounded:

**In scope**:
- Typed request/response models for every Fanar endpoint, the SSE chunk union, reference lists, and both thinking
  protocols.
- A pluggable, replaceable interceptor chain (ADR-012) for cross-cutting concerns.
- A pluggable, replaceable observability SPI (ADR-013) with vendor-neutral interfaces.
- HTTP transport and JSON serialization as explicit seams (ADR-007, ADR-008), not hard-coded choices.
- Stable extension points that let downstream modules bridge into any framework without forking.

**Out of scope**:
- Conversation / chat memory
- Prompt templating
- Vector stores and retrieval pipelines
- Structured-output synthesis (beyond what the Fanar API natively offers, which is nothing)
- Evaluation harnesses

Capabilities in the "out of scope" list are the responsibility of downstream framework modules — never core.

## Alternatives considered

- **Batteries-included scope** covering memory, templating, vectors, evaluation. *Rejected*: duplicates what Spring AI,
  LangChain4j, and others already do well; would age quickly; would fight the frameworks we want to integrate *with*.
- **Transport-only** with no SPI surface. *Rejected*: forces users to fork for even trivial cross-cutting concerns like
  auth rotation or retry policy tuning.

## Consequences

### Positive
- Small, stable surface area — fewer breaking changes over time.
- Respects the frameworks users have already chosen by integrating rather than competing.
- Faster path to a 1.0 release.
- Downstream modules become thin adapters — "a weekend, not a rewrite."

### Negative / Trade-offs
- Users looking for a single "complete toolkit" must compose us with another framework or build their own glue.
  Documentation needs to address this expectation upfront.
- A perception risk: "why is this SDK so minimal?" The answer lives in the docs; we must communicate it well.

### Neutral
- Every new feature proposal must first answer: "is this core responsibility, or framework-layer?"

## References

- [`docs/COMPATIBILITY.md`](../COMPATIBILITY.md) — §§2, 3 (core scope vs framework layer)
- [`README.md`](../../README.md) — "Why this SDK?"
- ADR-003 Framework-agnostic public API
- ADR-018 Internals are not a contract
