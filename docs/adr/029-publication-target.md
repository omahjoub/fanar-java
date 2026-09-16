# ADR-029 — Where published artifacts live, and what the coordinate has to be

- **Status**: Proposed
- **Date**: 2026-09-16
- **Deciders**: @omahjoub (pending an answer from the Fanar team)

## Context

The SDK is not consumable as a dependency. `release.yml` attaches jars to a GitHub Release, so a
user can *download* `fanar-core-0.5.0.jar`, but cannot write three lines of XML and build against
it. For a library, that is the difference between existing and being usable, and 1.0.0 should not
be declared while it holds.

The obvious answer — publish to Maven Central — runs into a constraint that is not obvious, and
that decides most of this record:

> **`groupId` is `qa.fanar`, which is reverse-DNS for `fanar.qa`.** The Sonatype Central Portal
> verifies a namespace by proving control of the corresponding domain. `fanar.qa` belongs to the
> Fanar team. **This project cannot publish its current coordinates to Maven Central on its own.**

Checked 2026-09-16: **the `qa.fanar` namespace does not exist on Maven Central.** Nobody has claimed
it — not us, and not the Fanar team. That makes path 1 heavier than "grant access": it is Sonatype
onboarding work for an organisation that may have no other reason to do it — create the account,
verify the domain, then add an outside maintainer as a publisher. A team happy in principle to see
the SDK exist can still reasonably decline to take that on, and that is a different answer from "no"
to the SDK itself. Worth separating the two when asking.

The same claim sits one level down, in the package names: `qa.fanar.core`, `qa.fanar.spring.boot.v4`
and the rest all assert the same domain. Whatever is true of the groupId is eventually true of them
(ADR-011, JLBP-6 — artifact id and package root correspond).

So the question is not "how do we publish", which is routine, but "under whose name", which is not
ours alone to answer. Three paths exist and they are not interchangeable:

1. **The Fanar team creates and verifies the `fanar.qa` namespace, then grants publish rights.**
   Needs them, and needs more of them than it first appears — see the note above.
2. **GitHub Packages.** Needs nobody.
3. **A namespace this project controls** — `io.github.omahjoub`, verified through the GitHub
   account, or a domain the maintainer owns. Needs nobody.

A fourth consideration cuts across all three: **publishing under `qa.fanar` reads as endorsement.**
Whether the Fanar team wants a third-party SDK under their namespace is a question about the
relationship, not about build tooling, and they may have a view on it independent of whether they
*can* grant access.

## Decision

The publication target is **open**, pending the Fanar team. Four things about it are not, and this
record fixes them so the open part is as small as possible.

### 1. Resolvable-as-a-dependency is a 1.0.0 requirement

GitHub Release assets stay as a convenience. They do not satisfy 1.0.0: a library nobody can declare
a dependency on has not shipped.

### 2. GitHub Packages is a channel, not a destination

It can serve either namespace and forecloses neither path 1 nor path 3, so it is available at any
time as an interim measure. It is not the end state, for one reason: **GitHub Packages requires
consumers to authenticate with a token even for public artifacts**, on top of adding a
`<repository>`. That is a real adoption tax, and it contradicts JLBP-21's "users should never need
to add a `<repository>`". Choosing it permanently would mean amending that rule deliberately rather
than quietly failing it.

### 3. If the groupId changes, the package root changes with it

Path 3 is not "edit sixteen POMs". ADR-011 and JLBP-6 make artifact id and package root correspond,
and the reason they do applies here with force: a package named `qa.fanar.*` claims a domain we do
not control, which is the same objection that blocks the groupId. Measured blast radius:

| | Count |
|---|---|
| POMs declaring `<groupId>qa.fanar</groupId>` | 16 |
| Java files under `qa/fanar/**` | 371 |
| Files mentioning `qa.fanar` at all | 425 |
| `exports qa.fanar.*` clauses in `module-info.java` | 17 |
| `META-INF/services` descriptors naming SPI types | 2 |
| GraalVM reachability-metadata files naming classes | 4 |
| Spring `AutoConfiguration.imports` files | 2 |

Mechanical, but it touches the two categories that fail *silently* rather than at compile time —
`META-INF/services` and the native-image metadata name classes as strings, so a missed rename
surfaces as "no codec found on the classpath" or a native-image failure at runtime, not as a
build error. The GraalVM self-test (ADR-009) is what catches the second class.

### 4. The decision expires at the 1.0.0 freeze

Renaming coordinates and packages is a find-and-replace before 1.0.0 and a fork after it. Under
ADR-019 the API freeze precedes 1.0.0, and this has to be settled **before that window opens**,
whichever way it goes. If no answer has arrived by then, **default to path 3** — a namespace we
control, shipped on time — rather than holding 1.0.0 open on someone else's calendar. Reversing
that later is cheap in the direction that matters: moving *to* `qa.fanar` after Fanar agrees is a
new coordinate they would be endorsing anyway, whereas waiting indefinitely costs the release.

### What is already done, because it holds on every path

- `-sources.jar` and `-javadoc.jar` per published module, via `./mvnw -Ppublish package`. Required
  by Central; harmless elsewhere; independently useful.
- Reproducible builds — `project.build.outputTimestamp` is pinned, verified by two builds of one
  commit producing a byte-identical jar.

Deliberately **not** done, because the path decides them: `distributionManagement`, and GPG signing
(required by Central, not by GitHub Packages).

## Alternatives considered

- **Publish under `qa.fanar` to Maven Central unilaterally.** *Not available*: namespace
  verification requires control of `fanar.qa`. Listed because it is the assumption the docs carried
  until 2026-09-16, and every earlier plan was built on it.
- **Keep `qa.fanar.*` packages under an `io.github.omahjoub` groupId.** *Rejected*: legal in Maven,
  and common in practice, but it leaves the package namespace claiming a domain we do not control —
  the exact objection that blocks the groupId. Half-renaming buys the churn without the honesty.
- **Ship only GitHub Release assets and call it published.** *Rejected*: see Decision 1.
- **Stay on GitHub Packages permanently.** *Rejected as an end state*: the token requirement is a
  standing tax on every consumer. Retained as an interim channel.
- **Wait for the Fanar team indefinitely.** *Rejected*: it makes the release date a function of
  someone else's inbox. Hence the expiry in Decision 4.
- **Vendor-neutral rename now** (a project-owned domain, e.g. a new one bought for it). *Not
  rejected, deferred*: strictly better than `io.github.*` for long-term identity, but it adds a
  domain purchase and DNS verification to the critical path. Worth doing if path 3 is taken and the
  maintainer wants the name to outlive the GitHub account.

## Consequences

### Positive
- The open question is reduced to one yes/no to one party, with a dated fallback, instead of an
  unbounded "Maven Central readiness" task.
- The path-independent prerequisites are already satisfied, so whichever answer arrives, the
  remaining work is small.
- The constraint is now written down. It had been invisible: every prior plan assumed `qa.fanar` on
  Central was ours to arrange.

### Negative / Trade-offs
- Path 3 costs a rename across 425 files, two of whose categories fail silently. Accepted: it is a
  single mechanical change, it is cheap only before 1.0, and the GraalVM self-test plus the codec
  `ServiceLoader` assertion cover the silent-failure classes.
- Until the decision lands, the SDK stays un-resolvable. Mitigated by GitHub Packages being
  available at any point as a stopgap.
- Under path 3 the artifacts no longer carry the Fanar name in their coordinate, which costs some
  discoverability for the exact search a user is most likely to run. Partly recoverable through the
  artifact id, the description and the README.

### Neutral
- Cross-module javadoc linking is disabled today (`detectOfflineLinks=false` in the root POM),
  because the links the plugin generates are derived from the project `<url>` and point at a site
  that does not exist. The proper fix is an explicit `<links>` entry aimed at published javadoc,
  which needs a published coordinate — so it resolves with this decision, not before it.
- Nothing about the module layout, the BOM or the release flow changes on any path. Only the
  coordinate and the deploy target do.

## References

- ADR-010 Module layout (which modules publish; the BOM covers every library module)
- ADR-011 Package conventions (artifact id ↔ package root correspondence)
- ADR-019 Pre-1.0 stability policy (the freeze this decision must precede)
- ADR-009 GraalVM native-image as a day-one CI target (catches string-named class renames)
- [`docs/JAVA_LIBRARY_BEST_PRACTICES.md`](../JAVA_LIBRARY_BEST_PRACTICES.md) — JLBP-6, JLBP-15/16,
  JLBP-21, and the Central prerequisites list
- [`docs/PROJECT_STATE.md`](../PROJECT_STATE.md) — the live status of the decision
- [`docs/RELEASING.md`](../RELEASING.md) — the GitHub-Release-era runbook this record will outgrow
