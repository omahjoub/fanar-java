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
- **What ships**: exactly 10 artifacts — 9 library jars (`fanar-core`, `fanar-json-jackson2`,
  `fanar-json-jackson3`, `fanar-obs-slf4j`, `fanar-obs-otel`, `fanar-obs-micrometer`,
  `fanar-interceptor-logging`, `fanar-spring-boot-4-starter`, `fanar-spring-ai-starter`) plus
  `fanar-java-bom-<V>.pom`. Sample apps and test modules are deliberately excluded.

## The checklist

Set these two variables once and paste commands as-is:

```bash
VERSION=0.2.0            # the version being released
NEXT=0.3.0-SNAPSHOT      # main's next development version
```

### 0 — Preflight (on `main`)

- [ ] CI green on `main` (Java 21 + 25 matrix, JaCoCo 100 %, doclint, dep-analyze).
- [ ] Live e2e run performed (`FANAR_API_KEY=… ./mvnw -pl e2e -am verify`) and every failure
      triaged: known-gated reds (documented in the test Javadocs) are expected; anything new
      gets fixed or explicitly accepted **before** releasing.
- [ ] GraalVM native smoke green (PR-time workflow on the last merged PR, or run the
      `graalvm.yml` dispatch).
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

### 3 — Dry-run the release workflow

GitHub → Actions → **Release** → *Run workflow* → Branch: `release/$VERSION`,
version: `$VERSION`, `dry_run: true`.

- [ ] *Verify pom version* step passed.
- [ ] Full build green (the release jars are exactly what this build produces).
- [ ] Download the `fanar-java-$VERSION` workflow artifact: exactly 10 files, every name ending
      in `$VERSION.jar` / `$VERSION.pom`, no `-SNAPSHOT` anywhere.

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
`Fanar Java SDK v$VERSION` with the 10 artifacts attached and auto-generated PR notes.

- [ ] Release page exists with all 10 assets.
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
      date) — can ride in the bump-back PR.
- [ ] Delete the `release/$VERSION` branch (the tag preserves the commit).
- [ ] Optional smoke: clone at the tag and `./mvnw install`, or resolve an attached jar into a
      scratch project.

## Troubleshooting

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
(9 library jars + `fanar-java-bom` for version alignment). Pair `fanar-core` with
`fanar-json-jackson3` (Jackson 3) or `fanar-json-jackson2` (Jackson 2).

**Full changelog:** [CHANGELOG.md](https://github.com/omahjoub/fanar-java/blob/v$VERSION/CHANGELOG.md) · [v<prev>...v$VERSION](https://github.com/omahjoub/fanar-java/compare/v<prev>...v$VERSION)
```

## When Maven Central arrives

This runbook covers the GitHub-Release era. The Central Portal flow (namespace verification,
GPG signing, `central-publishing-maven-plugin`, staging validation) will slot in between steps
3 and 5 — extend this file in the same PR that adds the publishing machinery, and revisit the
re-tag guidance above (Central is immutable).
