# Releasing — maintainer runbook

The replayable, copy-paste release process. `CONTRIBUTING.md` links here; this file is the
source of truth for cutting a release. When something goes wrong during a release, fix the
process **here** in the same PR as the fix — that's how the next release avoids the same hole.

## The invariants (why the process looks like this)

- **Pattern B ("release-and-bump")**: every tagged commit's `pom.xml` carries the exact release
  version — no `-SNAPSHOT` ever ships. `release.yml` enforces this with the
  *Verify pom version matches release version* step, which fails with a one-line fix
  instruction if the release branch wasn't prepared.
- **Only a tag push creates a real release.** `workflow_dispatch` exists for **dry runs** —
  running it with `dry_run: false` would create a release (and tag) from whatever ref you
  picked, bypassing the reviewed-main-commit guarantee. Don't.
- **The bump-back is part of the release**, not an afterthought. Until it merges, every dev
  build claims the released version, and re-releasing that version fails noisily.
- **What ships**: exactly 11 artifacts — 10 library jars (`fanar-core`, `fanar-json-jackson2`,
  `fanar-json-jackson3`, `fanar-obs-slf4j`, `fanar-obs-otel`, `fanar-obs-micrometer`,
  `fanar-interceptor-logging`, `fanar-spring-boot-4-starter`, `fanar-spring-ai-starter`,
  `fanar-adk`) plus `fanar-java-bom-<V>.pom`. Sample apps and test modules are deliberately excluded.

## The checklist

Set these two variables once and paste commands as-is:

```bash
VERSION=0.6.0            # the version being released — match the pom's current -SNAPSHOT
NEXT=0.7.0-SNAPSHOT      # main's next development version
```

### 0 — Preflight (on `main`)

- [ ] CI green on `main` — all three jobs: `test` (Java 21 + 25 matrix, JaCoCo 100 %, doclint,
      dep-analyze), `check-docs` **and** `check-build`.
- [ ] Live e2e run performed **on the release-candidate tree** — after the last code change that
      will ship; an earlier run that predates it does not count (0.4.0 was tagged on a run one day
      and four PRs old). Run `mkdir -p tasks && FANAR_API_KEY=… ./mvnw -pl e2e -am verify > "tasks/live-$(date +%F).log" 2>&1`,
      analyse the log against `docs/WIRE_OBSERVATIONS.md`, and triage every failure: the known-gated
      reds listed in the ledger's budget section are expected; anything new gets fixed or explicitly
      accepted **before** releasing. Mind the TTS window — a full run fits only ≥ 24 h after the
      previous run's first TTS call. (`tasks/` is git-ignored and absent from a fresh clone, hence the
      `mkdir`.)
- [ ] GraalVM native smoke green — and **confirm it actually ran**. `graalvm.yml` has no `push`
      trigger and a `paths:` filter, so a docs-only or `spring-*`-only PR produces no check at all,
      which looks the same as "nothing failed". If the last merged PR was outside its paths, trigger
      the `self-test` dispatch on `release/$VERSION`.
- [ ] `docs/PROJECT_STATE.md` reflects reality (its cadence rule: updated in the same PR as
      whatever moved).
- [ ] `CHANGELOG.md` `## [Unreleased]` is complete — every shipped change present, breaking
      changes marked **Breaking** with migration notes (ADR-019 requires the callout).
- [ ] Working tree clean; no stray untracked files that could ride along in commits.

### 1 — Finalize the changelog

Still on `main` (the edit is committed on the release branch in step 2):

- [ ] Rename `## [Unreleased]` → `## [$VERSION] - <today YYYY-MM-DD>` and add a 2–4 line intro
      paragraph (what the release is; pre-1.0 caveat; install note).
- [ ] Insert a fresh empty `## [Unreleased]` above it.
- [ ] Update the link refs at the bottom:

  ```
  [Unreleased]: https://github.com/omahjoub/fanar-java/compare/v$VERSION...HEAD
  [$VERSION]: https://github.com/omahjoub/fanar-java/releases/tag/v$VERSION
  ```

### 2 — Cut the release branch

```bash
git switch -c release/$VERSION main
./mvnw -B versions:set -DnewVersion=$VERSION -DgenerateBackupPoms=false
git add pom.xml '*/pom.xml' CHANGELOG.md
git commit -m "release: $VERSION"
git push -u origin release/$VERSION
```

- [ ] The commit touches only poms + `CHANGELOG.md` (add paths explicitly — never `git add -A`).
      The one admitted exception is a fix for a release surprise — something that only fails once
      the release branch exists. Those ride in this PR along with the runbook change that stops
      them recurring, rather than waiting for a follow-up (`CLAUDE.md`: a release surprise fixes
      the runbook in the same PR).

### 3 — Dry-run the release workflow

GitHub → Actions → **Release** → *Run workflow* → Branch: `release/$VERSION`,
version: `$VERSION`, `dry_run: true`.

- [ ] *Verify pom version* step passed.
- [ ] Full build green (the release jars are exactly what this build produces).
- [ ] Download the `fanar-java-$VERSION` workflow artifact: exactly 11 files, every name ending
      in `$VERSION.jar` / `$VERSION.pom`, no `-SNAPSHOT` anywhere.

### 3b — Consumer smoke (mandatory, and it must happen *here*)

Still on `release/$VERSION`, before anything is tagged. Everything up to this point built the
modules **inside the reactor**, where Maven resolves siblings from the build itself — it never
reads the BOM, never resolves a published coordinate, never puts a jar on the module path. The
reactor is therefore structurally unable to catch a packaging fault, which is why
`fanar-spring-ai-starter` was published but missing from the BOM for five releases with every
build green, and why `fanar-spring-boot-4-starter` shipped four times unable to go on the module
path at all. Both were found in 0.6.0 by doing this, and neither was visible to any other gate.

```bash
./mvnw install                       # populate ~/.m2 from the release branch
D=$(mktemp -d) && mkdir -p "$D/src/main/java/smoke"
cat > "$D/pom.xml" <<XML
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
  <groupId>smoke</groupId><artifactId>consumer</artifactId><version>1.0</version>
  <properties><maven.compiler.release>21</maven.compiler.release></properties>
  <dependencyManagement><dependencies><dependency>
    <groupId>qa.fanar</groupId><artifactId>fanar-java-bom</artifactId>
    <version>$VERSION</version><type>pom</type><scope>import</scope>
  </dependency></dependencies></dependencyManagement>
  <dependencies><!-- no <version> anywhere: the BOM must supply every one -->
    <dependency><groupId>qa.fanar</groupId><artifactId>fanar-core</artifactId></dependency>
    <dependency><groupId>qa.fanar</groupId><artifactId>fanar-json-jackson3</artifactId></dependency>
    <dependency><groupId>qa.fanar</groupId><artifactId>fanar-spring-ai-starter</artifactId></dependency>
    <dependency><groupId>qa.fanar</groupId><artifactId>fanar-adk</artifactId></dependency>
  </dependencies></project>
XML
cat > "$D/src/main/java/smoke/Smoke.java" <<'JAVA'
package smoke;
import qa.fanar.core.FanarClient;
public final class Smoke {
    public static void main(String[] a) {
        try (FanarClient c = FanarClient.builder().apiKey("sk_smoke").build()) {
            System.out.println("OK: codec resolved via ServiceLoader, chat=" + (c.chat() != null));
        }
    }
}
JAVA
./mvnw -q -f "$D/pom.xml" compile \
  && ./mvnw -q -f "$D/pom.xml" dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
java -cp "$D/target/classes:$(cat /tmp/cp.txt)" smoke.Smoke

M=~/.m2/repository/qa/fanar
java --module-path "$M/fanar-core/$VERSION/fanar-core-$VERSION.jar:$M/fanar-spring-boot-4-starter/$VERSION/fanar-spring-boot-4-starter-$VERSION.jar:$M/fanar-adk/$VERSION/fanar-adk-$VERSION.jar" --list-modules | grep fanar
```

- [ ] Every dependency resolves with **no `<version>`** declared — proves the BOM manages each one.
      A missing BOM entry fails here and nowhere else.
- [ ] The consumer compiles and `Smoke` prints `OK` — proves `ServiceLoader` finds the codec from a
      *repository*, which is a different resolution path from the reactor's.
- [ ] `--list-modules` names every jar (`qa.fanar.core@$VERSION`,
      `qa.fanar.spring.boot.v4@$VERSION automatic`, `qa.fanar.adk@$VERSION automatic`, …) —
      proves nothing derives an illegal automatic module name from its filename.
- [ ] Add any artifact whose packaging changed in this release to the dependency list above.

A failure here is free: fix on the release branch and re-run the dry-run. After the tag it costs a
re-tag, and once Central arrives it costs a patch version — Central is immutable.

### 4 — Release PR

- [ ] Open PR `release/$VERSION → main`. Review scope: the version bump + the finalized
      changelog section. Nothing else should be in the diff.
- [ ] Merge (squash or merge-commit — one commit either way).

### 5 — Tag: the actual release trigger

```bash
git switch main && git pull
git tag -a v$VERSION -m "Release $VERSION"
git push origin v$VERSION
```

The tag push fires `release.yml` for real: pom guard → full build → GitHub Release
`Fanar Java SDK v$VERSION` with the 11 artifacts attached and auto-generated PR notes.

- [ ] Release page exists with all 11 assets.
- [ ] Replace the auto-generated notes with curated notes (template below), keeping the
      auto-generated PR list at the bottom if useful.

### 6 — Bump main back (mandatory)

```bash
git switch -c bump/$NEXT main
./mvnw -B versions:set -DnewVersion=$NEXT -DgenerateBackupPoms=false
git add pom.xml '*/pom.xml'
git commit -m "build: bump to $NEXT"
git push -u origin bump/$NEXT
```

- [ ] PR → main, merge.

### 7 — Post-release

- [ ] Update `docs/PROJECT_STATE.md` (move the release from *Planned*, refresh the snapshot
      date and phase) — can ride in the bump-back PR.
- [ ] Bump the README quick-start version snippets to `$NEXT` — four `<version>` blocks that track
      `main`'s snapshot. `check-docs` enforces this **only while the reactor is on a `-SNAPSHOT`**,
      so a miss turns up in CI on `main` — fix it here rather than on a red `main`. The check is
      deliberately skipped on a release branch, where the reactor is `$VERSION` and the README is
      still on the old snapshot by design; it prints a line saying so.
- [ ] Delete the `release/$VERSION` branch (the tag preserves the commit).
- [ ] Confirmation build from the tag: `git clone --depth 1 --branch v$VERSION <repo> /tmp/rc &&
      cd /tmp/rc && ./mvnw install`. The consumer smoke already ran at step 3b against the same
      tree; this only adds proof that nothing load-bearing was left uncommitted. Optional, and
      cheap — unlike step 3b, a failure here is recoverable by re-tagging.

## Troubleshooting

**`check-docs` fails the release PR on the README version snippets.** Fixed 2026-09-16 during the
0.6.0 release, kept here because the shape recurs: a gate that compares a doc against the reactor
version must ask *which* version the doc is supposed to track. The README tracks `main`'s
development snapshot, so on a release branch — where `versions:set` has just dropped the
`-SNAPSHOT` — it is correct for them to disagree, and the release commit is scoped to poms plus
the changelog anyway. The check now enforces only while the reactor is on a `-SNAPSHOT`. If you
see this failure again, the check regressed; do not "fix" it by editing the README on the release
branch.

- **Pom-guard failure** ("pom.xml version is 'X-SNAPSHOT' but the release expects 'X'"): the
  `versions:set` commit is missing from the ref being released. The workflow's error message
  prints the fix; prepare the release branch (step 2) properly and re-run.
- **Release workflow failed after the tag push** (or the tag landed on the wrong commit): while
  we are *not* on Maven Central, tags are cheap to redo — delete the GitHub Release if it was
  created (`gh release delete v$VERSION`), delete the tag (`git push origin :refs/tags/v$VERSION`
  and `git tag -d v$VERSION`), fix, re-tag. **Once artifacts publish to Maven Central this
  stops being an option** — Central is immutable; a broken release then becomes a new patch
  version, never a re-tag.
- **Dry-run artifacts look wrong** (missing module, stray `-SNAPSHOT`): fix on the release
  branch, push, re-run the dispatch. Dry runs are free — iterate there, never on tags.
- **Forgot the bump-back** and the next `versions:set` conflicts or a dev build shipped a
  release version somewhere: do the bump-back immediately; it's idempotent.

## Release-notes template

```markdown
# $VERSION — <one-line theme>

<2–3 sentence headline: what this release is and why it exists.>

## Highlights
**<Capability>.** <1–3 sentences, name the entry-point types/methods, link the ADR if one exists.>

## ⚠️ Breaking changes (pre-1.0, ADR-019)
1. <What broke — and the one-line migration.>

## Install
Not yet on Maven Central: `./mvnw install` from a clone, or use the attached jars
(10 library jars + `fanar-java-bom` for version alignment). Pair `fanar-core` with
`fanar-json-jackson3` (Jackson 3) or `fanar-json-jackson2` (Jackson 2).

**Full changelog:** [CHANGELOG.md](https://github.com/omahjoub/fanar-java/blob/v$VERSION/CHANGELOG.md) · [v<prev>...v$VERSION](https://github.com/omahjoub/fanar-java/compare/v<prev>...v$VERSION)
```

## When Maven Central arrives

This runbook covers the GitHub-Release era. The Central Portal flow (namespace verification,
GPG signing, `central-publishing-maven-plugin`, staging validation) will slot in between steps
3 and 5 — extend this file in the same PR that adds the publishing machinery, and revisit the
re-tag guidance above (Central is immutable).
