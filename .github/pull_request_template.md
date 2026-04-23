## What does this PR do?

<!-- Brief description of the change. Focus on the "why" — the "what" is in the diff. -->

## Scope

<!-- Tick what this PR touches. At least one must be checked. -->

- [ ] Core module (`qa.fanar.core`)
- [ ] Downstream / framework module — which: `__________`
- [ ] BOM
- [ ] Docs / tooling / CI only

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Documentation
- [ ] Refactoring (no behavior change)
- [ ] New module

---

## Checklist

Items are split by scope. **Delete the block(s) that do not apply.** The rationale behind each item lives in
[`docs/JAVA_LIBRARY_BEST_PRACTICES.md`](../docs/JAVA_LIBRARY_BEST_PRACTICES.md) — that document is the source of truth,
this template mirrors it.

### Common — every PR

- [ ] I have read [`CONTRIBUTING.md`](../docs/CONTRIBUTING.md)
- [ ] Tests added or updated
- [ ] `mvn verify` passes locally
- [ ] `mvn dependency:analyze` reports no undeclared or unused direct dependencies
- [ ] No version ranges in dependency declarations
- [ ] No split packages across modules
- [ ] No shading anywhere in the build
- [ ] Minimum Java version unchanged — no API from a newer JDK accidentally used
- [ ] Deprecated API carries `@Deprecated(since, forRemoval)` and a migration path
- [ ] BOM updated if this PR adds or removes a module
- [ ] Javadoc builds clean on any new public API
- [ ] ADR added or updated if a design decision changed

### Core-module checklist

<!-- Delete this block if the PR does not touch the core module. -->

- [ ] No new dependency added to core
- [ ] No third-party types on public API surfaces
- [ ] `module-info.java` exports only the intended public packages
- [ ] `mvn dependency:tree` shows no unexpected transitives
- [ ] New public types are `sealed`, `final`, or explicitly designed for extension

### Downstream / framework-module checklist

<!-- Delete this block if the PR does not touch an adapter or framework-integration module. -->

- [ ] Core module untouched, or touched only to extend an existing public plug point
- [ ] Framework dependencies declared with `provided` scope
- [ ] No framework types leak back into core
- [ ] Module owns a distinct package subtree under `qa.fanar.<x>`
- [ ] Adapter is a thin wrapper — no capability duplicated from core
- [ ] Target ecosystem's minimum Java version respected
