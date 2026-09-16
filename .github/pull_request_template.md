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

- [ ] `./mvnw verify` passes locally
- [ ] Live tests pass against the real Fanar API (if applicable, with `FANAR_API_KEY` set)
- [ ] GraalVM `--self-test` passes (if the SDK's reflective surface changed)

## Reviewer checklist

<!-- Items CI can't check on its own — human judgment required. Tick or mark N/A. -->

- [ ] Public API changes are intentional, documented in javadoc, and an ADR was added/updated if the design changed
- [ ] Which `*IntegrationTest` proves the behaviour this PR claims? (100 % JaCoCo on a unit is not proof of wiring — see CONTRIBUTING "Testing")
- [ ] Breaking changes carry `@Deprecated(since, forRemoval = true)` and a migration path in the PR description
- [ ] No third-party types leak into `qa.fanar.core` public API
- [ ] If a non-obvious decision was made, it is captured where it belongs — an ADR under `docs/adr/` for design, a dated row in `docs/WIRE_OBSERVATIONS.md` for observed API behaviour, a `docs/PROJECT_STATE.md` entry for scope

<!--
The full engineering checklist (no version ranges, no split packages, dependency hygiene,
core-vs-adapter rules, native-image reachability, …) lives in
`docs/JAVA_LIBRARY_BEST_PRACTICES.md`.

CI runs on pushes to `main` and on PRs targeting `main`, and enforces four gates: JaCoCo 100 %,
`dependency:analyze` strict, doclint, and the full test suite on Java 21 and 25 — plus
`check-docs` (every relative link, `#fragment`, image and README version snippet) and
`check-build` (BOM completeness, string-named classes, coverage opt-outs). Everything else on that
list, including "no version ranges", is reviewer judgment. The boxes above are the part a machine
cannot check.
-->
