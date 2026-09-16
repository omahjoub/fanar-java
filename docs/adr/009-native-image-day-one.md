# ADR-009 — GraalVM native-image as a day-one CI target

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: @omahjoub (initial design)

## Context

The project's pitch is native-image support as a first-class target: *"GraalVM native-image workloads — reflection-free by design,
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

A dedicated `.github/workflows/graalvm.yml` pipeline runs a native-image smoke test on pull requests
that touch the SDK's native surface — core, the Jackson 3 codec, the observability adapters, the
logging interceptor or the probe module itself. It builds a native binary of `e2e-graalvm` and runs
it with `--self-test`: decode and encode probes across every domain, offline. Failure — missing
metadata, accidental reflection, incompatible API use — breaks the build.

Two scoping choices worth stating rather than discovering. The smoke covers **Jackson 3 only**: the
probe depends on one codec, and building it twice would double the slowest job in the matrix to
re-prove the same reflective surface through a near-identical adapter. And it is **path-filtered**,
so a docs-only or `spring-*`-only pull request runs no native build at all — which means "the
GraalVM check is green" and "the GraalVM check ran" are different statements, and a release
preflight has to confirm the second (`docs/RELEASING.md`).

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
- Turns the native-image claim from an assertion into something a build can fail on.

### Negative / Trade-offs
- A native-image build is the slowest thing in CI by a wide margin — minutes, against seconds for
  everything else. Path-filtering keeps it off the pull requests that cannot affect it; covering one
  codec rather than two keeps it to a single run.
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
