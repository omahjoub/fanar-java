# ADR-009 — GraalVM native-image as a day-one CI target

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

The project's public pitch (`README.md`) promises: *"GraalVM native-image workloads — reflection-free by design,
ready for serverless and edge."* Honoring that promise requires proactive maintenance of GraalVM reachability
metadata for the SDK's DTOs and deserialization paths. Without CI enforcement, metadata silently drifts as the code
evolves; the first break surfaces as a user bug report six months later, hurting trust.

Our architectural choices already align well: JDK `HttpClient` (ADR-007) has first-class native-image support,
virtual threads are supported from GraalVM 24+, records and sealed interfaces are AOT-friendly, and `ServiceLoader`
discovery (ADR-008) is picked up automatically at build time. The remaining friction is Jackson reflection on our
DTO types.

## Decision

**Native-image is a day-one CI target**. Each published artifact (`fanar-core`, `fanar-json-jackson2`,
`fanar-json-jackson3`) ships GraalVM reachability metadata under `META-INF/native-image/qa.fanar/<artifact>/`:

- `fanar-core` ships DTO reachability metadata (codec-agnostic; describes our types).
- `fanar-json-jackson2` ships Jackson-2-specific metadata (mixins, polymorphism discriminators, custom deserializers).
- `fanar-json-jackson3` ships Jackson-3-specific metadata (which is lighter thanks to Jackson 3's built-in
  reachability story).

The `.github/workflows/ci.yml` pipeline includes a native-image smoke test per Jackson adapter: build a native image
containing a minimal program, round-trip one `ChatRequest` and one SSE chunk, verify output. Failure — due to missing
metadata, accidental reflection, or incompatible API use — breaks the build.

## Alternatives considered

- **Ship metadata without CI tests**. *Rejected*: metadata rots silently; first break is always a user-filed bug.
- **Defer to users to supply metadata**. *Rejected*: hostile to adoption; every native-image user hand-writes the
  same JSON files. Contradicts the README promise.
- **No native-image commitment at all**. *Rejected*: contradicts the README promise; forecloses serverless and edge
  use cases that are a legitimate audience for this SDK.

## Consequences

### Positive
- Users building native images get a working experience on day one.
- Regressions (accidental reflection use, metadata drift) are caught at commit time.
- The metadata files are authoritative, version-controlled, reviewable documentation of what reflection our code
  performs.
- Strengthens the README promise from marketing claim to tested guarantee.

### Negative / Trade-offs
- Additional CI time per build (~1 minute per adapter for the native-image step). Acceptable; our build is otherwise
  fast.
- Metadata files are a maintenance burden we accept: adding a DTO field that Jackson reflects over requires a metadata
  update. Forgetting it breaks the native-image smoke test — which is exactly the guarantee we're buying.
- Third-party dependency updates (Jackson itself) can change reflection surface; metadata regenerates on the next
  update via the plugin or hand-edit.

### Neutral
- The smoke-test program is small and lives in CI, not as a distributed sample.

## References

- [`README.md`](../../README.md) — "GraalVM native-image" audience bullet
- [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)
- ADR-007 JDK `HttpClient` as the default transport
- ADR-008 JSON as an SPI with two Jackson adapters
- GraalVM Reachability Metadata documentation
