# Java Library Best Practices

These are the library-hygiene rules we hold ourselves to. They are **informed by** the
[Google Best Practices for Java Libraries](https://jlbp.dev) (JLBP) but written in our own voice — if JLBP evolves, or
if we decide a rule doesn't fit our context, we revise this doc on our own schedule. The section headings below mirror
JLBP-1..22 today for traceability with the external source; that correspondence is a convenience, not a contract.

Treat the rules as they stand here as the source of truth, and treat violations as bugs.

---

## JLBP-1: Minimize dependencies

core module has zero external dependencies — `java.base` and `java.net.http` only. 
Framework modules declare dependencies as `provided` so they don't leak into the user's dependency
tree.

Before adding any dependency, ask: can we reimplement this in <100 lines? If yes, don't add it. Run
`mvn dependency:tree` on every module before merging.

## JLBP-2: Minimize API surface

- **Public API = top-level package + `spi` subpackage** (e.g. `qa.fanar.core` for domain types and facades; `qa.fanar.core.spi` for extension interfaces). Everything under `internal` is non-public. `module-info.java` exports only the public packages and never exports internal ones.
- **Internals are free to refactor.** Code under `internal.*` may be rewritten, replaced, or deleted in any release without deprecation cycles. It is *not* a contract. This is the guarantee that lets the core evolve its HTTP transport, SSE parser, retry machinery, connection pooling, JSON-codec invocation, etc., without breaking downstream modules.
- **No internal types on the public surface.** Public API signatures cannot reference `internal.*` types — the module boundary enforces this at compile time.
- **No third-party types on the public surface.** Method signatures use JDK types (`Flow.Publisher`, `CompletableFuture`, `URI`, `Duration`, `InputStream`) and our own DTOs (`ChatRequest`, `StreamEvent`) — never third-party reactive, HTTP-client, or serialization types.
- Classes are `sealed` or `final` unless explicitly designed for extension.
- Methods are not `public` by default. Justify every public method.

## JLBP-3: Use semantic versioning

We follow [semver.org](https://semver.org) strictly.

- **Patch** (0.1.1): bug fixes, no API changes.
- **Minor** (0.2.0): new features, new `Optional` fields, new enum values. Backward compatible.
- **Major** (1.0.0 → 2.0.0): breaking changes — removed types, changed method signatures, dropped Java version support.

Pre-1.0 stability is covered under JLBP-12.

## JLBP-4: Avoid dependencies on unstable libraries and features

- Do not depend on SNAPSHOT or milestone versions of any library unless accepted in an ADR.
- Do not use Java preview features (e.g., `--enable-preview`). Stick to finalized features available in our minimum Java
  version.
- Do not depend on libraries that are pre-1.0 unless they are widely adopted and stable in practice.

## JLBP-5: Do not include a class in more than one classpath entry

No class or package appears in more than one of our modules. Each module owns a distinct package subtree, enforced by
JPMS `module-info.java` exports.

## JLBP-6: Rename artifacts and packages together

If we ever rename an artifact (we shouldn't), the package must change with it. Naming is aligned 1:1 — artifact
`fanar-java-<x>` lives under package root `qa.fanar.<x>`. Never break that correspondence.

## JLBP-7: Make breaking transitions easy

- Deprecate before removing. Mark with `@Deprecated(since = "x.y.z", forRemoval = true)`.
- Provide migration path in the deprecation Javadoc (`@see` or `@link` to replacement).
- Keep deprecated API working for at least one minor release before removing in the next major.

## JLBP-8: Advance widely used functionality to a stable version

Do not keep public APIs in `@Beta` or `@Experimental` longer than two minor releases. If it's used, stabilize it. If
it's not used, remove it.

## JLBP-9: Support the minimum Java version of your consumers

- **Core module: Java 21 LTS** — broad enterprise adoption since September 2023, and it already gives us sealed types,
  records, pattern matching, `java.net.http.HttpClient`, and `java.util.concurrent.Flow`. Enough to build the SDK
  without backporting anything we need.
- **Framework modules: match their target ecosystem's minimum Java version** — higher is fine when the ecosystem
  requires it; never lower than core.
- **CI test matrix** covers the declared minimum **and** the current LTS (and any preview of the next LTS once it exists).
- `maven.compiler.release` is the single source of truth — never call an API introduced in a later version.

## JLBP-10: Maintain API stability as long as needed for consumers

- Sealed types are the API contract. Adding a new variant to a sealed hierarchy is a breaking change (forces consumers
  to update their `switch` expressions). Do this only in minor versions and document it clearly.
- Record component names are part of the API. Do not rename them in patch releases.
- Enum values are part of the API. New values are minor releases. Removing values is a major release.

## JLBP-11: Keep dependencies up to date

- Enable Dependabot or Renovate on the repository for automated dependency update PRs.
- Review and merge dependency updates promptly — stale dependencies are a security risk.
- Run `mvn versions:display-dependency-updates` periodically.

## JLBP-12: Make level of support and API stability clear

- The README states supported Java versions and framework versions.
- Pre-1.0: API may change. State this explicitly in the README.
- Post-1.0: semver applies. Public API is stable within a major version.
- Each module's Javadoc root page states its stability level.

## JLBP-13: Remove references to deprecated features in dependencies at the first opportunity

When a dependency deprecates a type or method we use, migrate away in the next SDK release. Do not accumulate
deprecation warnings — they indicate future breakage.

## JLBP-14: Specify a single, overridable version of each dependency

- All dependency versions are declared in the root POM's `<dependencyManagement>` via `<properties>`.
- No version ranges. No dynamic versions. No `[2.3,)` or `LATEST`.

## JLBP-15: Publish a BOM for multi-module projects

`fanar-java-bom` is a `<packaging>pom</packaging>` artifact containing `<dependencyManagement>` for all modules. Users
import it once and declare modules without versions:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>qa.fanar</groupId>
            <artifactId>fanar-java-bom</artifactId>
            <version>${fanar.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## JLBP-16: Ensure upper version alignment of dependencies for consumers

The BOM pins all inter-module versions. When a user imports the BOM, all `fanar-java-*` modules resolve to the same
version. No mixed-version scenarios.

Framework dependencies are `provided` — the user's application controls their
versions, not our BOM. This prevents version conflicts.

## JLBP-17: Coordinate rollout of breaking changes

- All modules in a release share the same version number.
- Breaking changes are announced at least one minor release in advance via deprecation.
- Migration guides are published with every major release.
- The BOM ensures consumers upgrade all modules together.

## JLBP-18: Only shade dependencies as a last resort

We do not shade. Ever. Framework modules use `provided` scope — the user
supplies the runtime version. If a conflict arises, the correct fix is to align versions via the BOM, not to shade.

## JLBP-19: Place each package in only one module

Package layout (see JLBP-5) gives each module a distinct subtree. `module-info.java` ships with every
module — JPMS enforces this at compile time. No split packages.

## JLBP-20: Give each JAR file a module name

Every code module declares a `module-info.java` with an explicit module name:

`fanar-java-bom` has no classes and no JPMS descriptor. If `module-info.java` is not feasible for a module (e.g., due to
framework constraints with classpath scanning), use `Automatic-Module-Name` in `MANIFEST.MF` as a fallback.

## JLBP-21: Upload artifacts to Maven Central

All release artifacts are published to **Maven Central** via the Sonatype **Central Portal** (the successor to OSSRH).
No custom repositories. Users should never need to add a `<repository>` to use our library.

SNAPSHOTs may be published to the Central Portal's snapshot repository during development.

## JLBP-22: Declare all direct dependencies

Every class we import from outside the module must come from a declared dependency. Do not rely on transitive
dependencies — they can disappear when a dependency updates.

Enforce with `mvn dependency:analyze`. Treat undeclared-but-used dependencies as build failures.

---

## Beyond JLBP — Central-grade expectations

Not in the 22, but table stakes for a library claiming global visibility. Treat these with the same "violations are
bugs" rigor as the practices above.

### Reproducible builds

`project.build.outputTimestamp` is pinned in the root POM so every release builds byte-for-byte identically from the
same commit. Verify with `reproducible-central` or the `artifact:compare` Maven goal before tagging a release.

### Artifact signing, SBOM, provenance

- **GPG-signed artifacts** on every release (required by Maven Central).
- **CycloneDX SBOM** generated per module and attached to each release.
- **Sigstore / SLSA provenance** for release artifacts so downstream consumers can cryptographically verify they came
  from our CI pipeline, not a compromised laptop.

### API freeze before 1.0.0

No new public API in the two weeks before 1.0. Bug fixes, docs, and tests only. A stable target makes JLBP-10
enforceable the moment 1.0 ships, and signals to early adopters that the surface is settled.

### Javadoc quality gate

`-Xdoclint:all,-missing` on public API. The build fails on dangling `@link`, malformed tags, or broken inheritance.
Javadoc is part of the API contract — if it is wrong, the API surface is wrong.

### Non-JPMS consumers are first-class

Consumers on plain classpath (no `module-info.java` of their own) must not be penalized. Every module ships an
`Automatic-Module-Name` in its `MANIFEST.MF` as a fallback, even when the module also has `module-info.java`.

---

## Summary checklist for PRs

The checklist below is the **source of truth** — [`.github/pull_request_template.md`](../.github/pull_request_template.md)
mirrors it, so any change here must be reflected there too. Items are split by scope.

### Common — every PR

- [ ] `mvn dependency:analyze` reports no undeclared or unused direct dependencies
- [ ] No version ranges in dependency declarations
- [ ] No split packages across modules
- [ ] No shading anywhere in the build
- [ ] Minimum Java version unchanged — no API from a newer JDK accidentally used
- [ ] Deprecated API carries `@Deprecated(since, forRemoval)` and a migration path
- [ ] BOM updated if this PR adds or removes a module
- [ ] Javadoc builds clean on any new public API

### Core-module PRs — touch the core client

<!-- Use this block when the PR changes qa.fanar.core or its public API. -->

- [ ] No new dependency added to core
- [ ] No third-party types on public API surfaces
- [ ] `module-info.java` exports only the intended public packages
- [ ] `mvn dependency:tree` shows no unexpected transitives
- [ ] New public types are `sealed`, `final`, or explicitly designed for extension

### Downstream / framework-module PRs — adapters, starters, ecosystem wiring

<!-- Use this block when the PR changes an adapter or framework-integration module. -->

- [ ] Core module untouched, or touched only to extend an existing public plug point
- [ ] Framework dependencies declared with `provided` scope
- [ ] No framework types leak back into core
- [ ] Module owns a distinct package subtree under `qa.fanar.<x>`
- [ ] Adapter is a thin wrapper — no capability duplicated from core
- [ ] Target ecosystem's minimum Java version respected
