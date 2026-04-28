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
  Forcing JPMS here would push that requirement down to consuming apps for no benefit. The jar
  still gets an automatic module name from its artifact id for projects that opt into the
  module path.
- **`spring-boot-health` is `provided + optional`.** Apps without actuator pay zero dep
  footprint; apps with actuator activate the health contributor automatically. The split into
  two auto-config classes is what makes this clean — `@ConditionalOnClass(HealthIndicator.class)`
  only gates the indicator config, not the client config.
- **Default `FanarJsonCodec` bean wired explicitly** (Jackson 3 codec) instead of relying on the
  SDK's `ServiceLoader`. AOT processing and module-path classloaders sometimes can't cross the
  JPMS provider boundary cleanly; an explicit bean sidesteps that.
- **Auto-wired contributor beans.** User-defined `Interceptor` and `ObservabilityPlugin` beans
  are picked up via `ObjectProvider` on the `fanarClient` bean and added to the builder — no
  extra annotation or registration needed by the user.

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
- ⚠ The starter is classpath-only. JPMS-strict consumers wanting full module-path purity have to
  deal with the automatic-module-name posture.

## References

- [`fanar-spring-boot-4-starter`](../../spring-boot-4-starter/) — the module.
- ADR-002 — narrow core SDK scope (auto-config is downstream).
- ADR-021 — Spring AI 2.0 adapter (sits on top of this).
