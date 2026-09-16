# ADR-020 — Spring Boot 4 starter shape

- **Status**: Accepted
- **Date**: 2026-04-26
- **Deciders**: @omahjoub

## Context

ADR-002 carved out auto-configuration as a downstream concern. The first downstream framework
adapter is for Spring Boot 4 — the framework most enterprise Java AI work is shipped on top of.
Three shape decisions were on the table:

1. Single-module starter vs split (auto-config + properties + actuator each their own module).
2. Module-path (`module-info.java`) vs classpath posture for the starter jar itself.
3. Coupling to `spring-boot-actuator` — required, optional, or absent.

Each had to fit the constraint that consumers add **one dep**, set `fanar.api-key=…`, and get a
working `FanarClient` bean — with no surprise on classpaths that lack actuator.

## Decision

- **One module, two auto-configs.** `FanarAutoConfiguration` builds the `FanarClient` bean from
  `FanarProperties`. `FanarHealthAutoConfiguration` is separate and registers the
  `FanarHealthIndicator` *only* when `spring-boot-health` is on the classpath. Both are listed in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- **Classpath posture, no `module-info.java`.** Spring Boot 4's auto-configuration and Actuator
  wiring run on the classpath, and most Spring Framework jars don't declare full JPMS modules.
  Forcing JPMS here would push that requirement down to consuming apps for no benefit.
- **An explicit `Automatic-Module-Name` in the manifest**, `qa.fanar.spring.boot.v4`. Without a
  descriptor JPMS falls back to deriving a name from the *filename*, and derivation **fails** here:
  `fanar-spring-boot-4-starter` yields the component `4`, which is not a Java identifier, so
  `jar --describe-module` reports an invalid module name and the jar cannot go on the module path at
  all. Naming it in the manifest costs one line and restores the fallback the paragraph above
  assumes. The name matches the package root (ADR-011) rather than the filename.
- **`spring-boot-health` is `provided + optional`.** Apps without actuator pay zero dep
  footprint; apps with actuator activate the health contributor automatically. The split into
  two auto-config classes is what makes this clean — `@ConditionalOnClass(HealthIndicator.class)`
  only gates the indicator config, not the client config.
- **Default `FanarJsonCodec` bean wired explicitly** (Jackson 3 codec) instead of relying on the
  SDK's `ServiceLoader`. AOT processing and module-path classloaders sometimes can't cross the
  JPMS provider boundary cleanly; an explicit bean sidesteps that.
- **Auto-wired contributor beans.** User-defined `Interceptor` beans are picked up via
  `ObjectProvider` on the `fanarClient` bean and added in `@Order` sequence; a single
  `ObservabilityPlugin` bean is installed the same way. Declaring more than one plugin means
  declaring an `ObservabilityPlugin.compose(...)` bean (ADR-022) — the slot is singular by design
  (ADR-013).
- **Replaceable default beans.** `FanarJsonCodec` and `RetryPolicy` are `@ConditionalOnMissingBean`
  beans: a user-declared bean of either type replaces the default without re-wiring the client.
- **Every retry knob reachable from configuration**, not just the two the starter first exposed.
  `fanar.retry.max-attempts`, `.initial-backoff`, `.max-delay` and `.max-total-delay` map onto the
  core defaults and are built through `RetryPolicy.builder()`. Building through the builder rather
  than assembling fields is the decision, not an implementation detail: the knobs are
  interdependent (`initial-backoff ≤ max-delay ≤ max-total-delay`), so a bad combination fails the
  context at startup with the policy's own message instead of silently misconfiguring the client.
  A knob that cannot be set is a knob a consumer has to fork the bean to reach — which is what
  pinning `maxDelay` at 30 s did until `maxDelay` became the `Retry-After` ceiling (ADR-025) and
  made "raise it" a real requirement.

## Alternatives considered

- **Three separate modules (`-starter-config`, `-starter-actuator`, `-starter`)** — overkill for
  the scope. The conditional gating already gives apps without actuator a zero-footprint
  install; splitting into three modules would force users to think about composition.
- **Use Spring Boot's `ServiceLoader` discovery for `FanarJsonCodec`** — works in plain JAR mode
  but breaks under AOT and on the module path because of the cross-module reflection that
  `ServiceLoader` performs. Direct bean wiring is simpler and AOT-safe.
- **Require actuator** — narrows the audience for no upside.

## Consequences

- ✅ One dep, one property, working client. The "drop in and go" promise.
- ✅ Apps without actuator pay nothing for the health indicator's existence.
- ✅ AOT-friendly out of the box.
- ⚠ The starter carries no module descriptor, so JPMS-strict consumers get an *automatic* module —
  named, usable on the module path, but without encapsulation guarantees. Full module-path purity
  would mean a `module-info.java`, which the first bullet rejects.

## References

- [`fanar-spring-boot-4-starter`](../../spring-boot-4-starter/) — the module.
- ADR-002 — narrow core SDK scope (auto-config is downstream).
- ADR-021 — Spring AI 2.0 adapter (sits on top of this).
- ADR-014 / ADR-025 — retry policy defaults and Retry-After handling (the knobs the starter exposes).
