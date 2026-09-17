# Java Library Best Practices

These are the library-hygiene rules we hold ourselves to. They are **informed by** the
[Google Best Practices for Java Libraries](https://jlbp.dev) (JLBP) but written in our own voice — if JLBP evolves, or
if we decide a rule doesn't fit our context, we revise this doc on our own schedule. The section headings below mirror
JLBP-1..22 today for traceability with the external source; that correspondence is a convenience, not a contract.

Treat the rules as they stand here as the source of truth, and treat violations as bugs.

---

## JLBP-1: Minimize dependencies

The core module has zero external dependencies — `java.base` and `java.net.http` only.

Framework modules declare dependencies as `provided` so they don't leak into the user's dependency
tree.

Before adding any dependency, ask: can we reimplement this in <100 lines? If yes, don't add it. To inspect a
module's tree use `./mvnw -pl <module> -am dependency:tree` — the `-am` is required, because the unpublished
`test-support` fixture only exists inside the reactor.

## JLBP-2: Minimize API surface

- **Public API = the top-level package, the domain subpackages, and `spi`.** For core that is `qa.fanar.core` (client, exceptions, policy types), the nine domain packages it exports (`chat`, `audio`, `images`, `translations`, `poems`, `moderations`, `sadiq`, `tokens`, `models`), and `qa.fanar.core.spi` for extension interfaces. Everything under `internal` is non-public. `module-info.java` exports exactly the public set and never an internal one — the descriptor is the authoritative list (ADR-011).
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

If we ever rename an artifact (we shouldn't), the package must change with it. Artifact id and package root
correspond 1:1: `fanar-core` ↔ `qa.fanar.core`, `fanar-json-jackson3` ↔ `qa.fanar.json.jackson3`,
`fanar-obs-otel` ↔ `qa.fanar.obs.otel`. Where an artifact id contains something that is not a legal Java
identifier the package root spells it out instead — `fanar-spring-boot-4-starter` ↔ `qa.fanar.spring.boot.v4` —
and the jar's `Automatic-Module-Name` follows the package root, not the filename. Never break that correspondence.

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
- **CI test matrix** covers the declared minimum and the current LTS — Java 21 and 25 today (`.github/workflows/ci.yml`). Both entries move together when the floor or the LTS target does.
- `maven.compiler.release` is the single source of truth — never call an API introduced in a later version.

## JLBP-10: Maintain API stability as long as needed for consumers

- Sealed types are the API contract. Adding a new variant to a sealed hierarchy is a breaking change (forces consumers
  to update their `switch` expressions). Do this only in minor versions and document it clearly.
- Record component names are part of the API. Do not rename them in patch releases.
- Enum values are part of the API. New values are minor releases. Removing values is a major release.

## JLBP-11: Keep dependencies up to date

- Dependabot is enabled (`.github/dependabot.yml`) and opens update PRs automatically.
- Review and merge them promptly — stale dependencies are a security risk.
- `./mvnw versions:display-dependency-updates` gives the same picture on demand.

## JLBP-12: Make level of support and API stability clear

- The README states the required Java version and the framework versions the starters target.
- Pre-1.0: API may change. Stated explicitly in the README and governed by ADR-019.
- Post-1.0: semver applies. Public API is stable within a major version.
- Each module's `package-info.java` states what is public API and what is internal (ADR-011, ADR-018).

## JLBP-13: Remove references to deprecated features in dependencies at the first opportunity

When a dependency deprecates a type or method we use, migrate away in the next SDK release. Do not accumulate
deprecation warnings — they indicate future breakage.

## JLBP-14: Specify a single, overridable version of each dependency

- All dependency versions are declared in the root POM's `<dependencyManagement>` via `<properties>`.
- No version ranges. No dynamic versions. No `[2.3,)` or `LATEST`.

## JLBP-15: Publish a BOM for multi-module projects

`fanar-java-bom` is a `<packaging>pom</packaging>` artifact managing **every published library module** — samples,
the live e2e suite, the native-image probe and the test fixture are never published and never appear in it. Adding a
library module means adding a BOM entry in the same change; a module that ships but is missing from the BOM is one a
consumer has to version by hand, which is the mixed-version problem the BOM exists to prevent. Users import it once
and declare modules without versions:

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

The BOM pins all inter-module versions. When a user imports the BOM, every Fanar module resolves to the same
version. No mixed-version scenarios.

Framework dependencies are `provided` — the user's application controls their
versions, not our BOM. This prevents version conflicts.

## JLBP-17: Coordinate rollout of breaking changes

- All modules in a release share the same version number.
- Breaking changes are announced at least one minor release in advance via deprecation.
- Migration guides are published with every major release.
- The BOM ensures consumers upgrade all modules together.

## JLBP-18: Only shade dependencies as a last resort

**No published artifact shades anything.** Framework modules use `provided` scope — the user supplies the runtime
version — and if a conflict arises the fix is to align versions via the BOM. The one shade in the build is
`e2e-graalvm`, which packs itself into an uber-jar so the GraalVM tracing agent has a single jar to run (ADR-009);
it is never published, so no consumer classpath ever sees it.

## JLBP-19: Place each package in only one module

Package layout (see JLBP-5) gives each module a distinct subtree. The seven library modules that can carry a `module-info.java` do, and JPMS enforces no-split-packages at compile
time for them. The two Spring starters and `fanar-adk` carry none by design (ADR-020, ADR-030), so the rule rests on
review there — each module still owns a distinct subtree.

## JLBP-20: Give each JAR file a module name

Every published jar resolves to a stable module name, by one of two routes:

- **A `module-info.java`** — core, both JSON codecs, the three observability adapters, the logging interceptor.
- **An `Automatic-Module-Name` manifest entry** — the two Spring starters, which carry no descriptor because
  Spring's classpath scanning and `@AutoConfiguration` predate a clean JPMS story (ADR-020), and `fanar-adk`, which
  carries none because ADK, google-genai and RxJava run on the classpath and declare no JPMS modules (ADR-030).
  This is not optional for the starters: without it JPMS derives the name from the *filename*, and
  `fanar-spring-boot-4-starter` derives `fanar.spring.boot.4.starter` — `4` is not a Java identifier, so derivation
  fails and the jar cannot go on the module path at all. For `fanar-adk` derivation would succeed (`fanar.adk`); the
  entry exists to keep the module name on the package root (ADR-011).

`fanar-java-bom` has no classes and needs neither. A module that has a descriptor does not also need the manifest
entry: JPMS ignores it when a descriptor is present.

## JLBP-21: Upload artifacts to Maven Central

**Undecided, and blocked on a coordinate we do not own.** `groupId` is `qa.fanar` — reverse-DNS for `fanar.qa` —
and Sonatype Central verifies a namespace by proving control of the domain, which belongs to the Fanar team. The
three candidate paths and their costs are set out in [`docs/PROJECT_STATE.md`](PROJECT_STATE.md); today the
artifacts ship as GitHub Release assets ([`docs/RELEASING.md`](RELEASING.md)).

What holds regardless: **this build pulls from no custom repository**, so a consumer's *resolution* is plain
Central — verifiable with `./mvnw -o clean verify` on a warm local repository. Note that one of the three paths
(GitHub Packages) would require consumers to add a repository *and* authenticate, so choosing it means amending
this rule rather than quietly breaking it.

## JLBP-22: Declare all direct dependencies

Every class we import from outside the module must come from a declared dependency. Do not rely on transitive
dependencies — they can disappear when a dependency updates.

Enforced by `maven-dependency-plugin:analyze-only` with `failOnWarning`, bound to `verify` in the root POM, so
`./mvnw verify` is the check. A bare `mvn dependency:analyze` is **not** the same thing: run from the command line
the goal picks up neither the fail-on-warning setting nor the ignore-list nested in the execution, so it cannot
fail and reports false positives the real gate deliberately ignores.

---

## Beyond JLBP — Central-grade expectations

Not in the 22, but table stakes for a library claiming global visibility. **This section is split by what is true
today and what is not** — an aspiration written in the present tense is how a checklist stops being checkable.

### In force

**Javadoc quality gate.** `-Xdoclint:all,-missing` runs at javac time on every module, so a dangling `@link`,
a malformed tag or a broken inheritance chain fails the build. Javadoc is part of the API contract — if it is
wrong, the API surface is wrong. Note the scope: this is doclint, not a full `maven-javadoc-plugin` run, so it
checks the source and does not produce HTML.

**API freeze before 1.0.0.** No new public API in the two weeks before 1.0 — fixes, docs and tests only
(ADR-019). A stable target makes JLBP-10 enforceable the moment 1.0 ships, and signals to early adopters that the
surface is settled. Anything that must exist in 1.0 lands before the freeze opens, because after 1.0 an addition to
a sealed hierarchy or a rename is a major-version event.

**Non-JPMS consumers are first-class.** Consumers on a plain classpath are never penalised, and every published jar
resolves to a stable module name — by descriptor where that is possible, by `Automatic-Module-Name` where it is not
(JLBP-20).

### Prerequisites for Maven Central — not yet implemented

These are required to publish at all, so they gate the Central move rather than the next release. Tracked in
[`docs/PROJECT_STATE.md`](PROJECT_STATE.md).

Two are **done**, because they hold whichever publication path we take:

- **`-sources.jar` and `-javadoc.jar` per published module**, via `./mvnw -Ppublish package`. Opt-in rather than
  part of every build: a full javadoc run is slower than the rest of the reactor combined and proves nothing a
  contributor needs proved per commit. The javadoc run is also a stricter check than javac's doclint — it resolves
  cross-module `@link` targets and actually renders the HTML.
- **Reproducible builds** — `project.build.outputTimestamp` is pinned in the root POM, so the same commit produces
  byte-identical archives (verified: two builds, one SHA-256). Bump it to the release date when cutting a release
  and confirm with `artifact:compare`.

Three remain, and two of the three are **path-dependent** — see the publication decision in
[`docs/PROJECT_STATE.md`](PROJECT_STATE.md):

- **`distributionManagement`** — the repository to deploy to. Cannot be written until the path is chosen.
- **GPG-signed artifacts.** Required by Maven Central; *not* required by GitHub Packages. No `maven-gpg-plugin`
  today.
- **CycloneDX SBOM**, and optionally Sigstore / SLSA provenance, so consumers can verify an artifact came from CI
  rather than a laptop. Neither is generated today and neither gates publication.

---

## Summary checklist for PRs

The checklist below is the **full engineering list**, split by scope.
[`.github/pull_request_template.md`](../.github/pull_request_template.md) does **not** mirror it — it carries the
short human-judgment residue that fits in a PR form and points here for the rest. Keeping them separate is
deliberate; keeping them in sync was not working.

### Common — every PR

- [ ] `./mvnw verify` is green — it runs the dependency, coverage and doclint gates together
- [ ] No version ranges in dependency declarations
- [ ] No split packages across modules
- [ ] No shading in anything that gets published
- [ ] Minimum Java version unchanged — no API from a newer JDK accidentally used
- [ ] Deprecated API carries `@Deprecated(since, forRemoval)` and a migration path
- [x] BOM updated if this PR adds or removes a published module — *enforced* by `check-build`
- [ ] Doclint passes on any new public API
- [ ] Every consumer-observable behaviour the PR claims is proved by a seam-crossing `*IntegrationTest` through the public API — JaCoCo measures execution, not integration (see [CONTRIBUTING → Testing](CONTRIBUTING.md#testing))

### Core-module PRs — touch the core client

<!-- Use this block when the PR changes qa.fanar.core or its public API. -->

- [ ] No new dependency added to core
- [ ] No third-party types on public API surfaces
- [ ] `module-info.java` exports only the intended public packages
- [ ] `./mvnw -pl core -am dependency:tree` shows no unexpected transitives
- [ ] New public types are `sealed`, `final`, or explicitly designed for extension

### Downstream / framework-module PRs — adapters, starters, ecosystem wiring

<!-- Use this block when the PR changes an adapter or framework-integration module. -->

- [ ] Core module untouched, or touched only to extend an existing public plug point
- [ ] Framework dependencies declared with `provided` scope
- [ ] No framework types leak back into core
- [ ] Module owns a distinct package subtree under `qa.fanar.<x>`
- [ ] Adapter is a thin wrapper — no capability duplicated from core
- [ ] Target ecosystem's minimum Java version respected
