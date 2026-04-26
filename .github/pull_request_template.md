## Summary

<!-- 1–3 bullets. Lead with the "why" — the "what" is in the diff. Link a related issue or ADR. -->

-

## Type of change

- [ ] Bug fix
- [ ] New feature / capability
- [ ] New module
- [ ] Refactoring (no behavior change)
- [ ] Breaking change
- [ ] Documentation / tooling / CI only

## Test plan

- [ ] `mvn verify` passes locally
- [ ] Live tests pass against the real Fanar API (if applicable, with `FANAR_API_KEY` set)
- [ ] GraalVM `--self-test` passes (if the SDK's reflective surface changed)

## Reviewer checklist

<!-- Items CI can't check on its own — human judgment required. Tick or mark N/A. -->

- [ ] Public API changes are intentional, documented in javadoc, and an ADR was added/updated if the design changed
- [ ] Breaking changes carry `@Deprecated(since, forRemoval = true)` and a migration path in the PR description
- [ ] BOM updated if modules were added or removed
- [ ] No third-party types leak into `qa.fanar.core` public API
- [ ] If a non-obvious project decision was made, capture it in `memory/` so future contributors find it

<!--
The full engineering checklist (no version ranges, no split packages, dependency hygiene,
core-vs-adapter rules, native-image reachability, …) lives in
`docs/JAVA_LIBRARY_BEST_PRACTICES.md`. CI enforces every machine-checkable item from there on
every push — the boxes above are the human-judgment residue.
-->
