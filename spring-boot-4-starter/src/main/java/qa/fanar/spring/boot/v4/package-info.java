/**
 * Spring Boot 4 auto-configuration for the Fanar Java SDK.
 *
 * <p>Public types: {@link qa.fanar.spring.boot.v4.FanarProperties} (typed
 * {@code @ConfigurationProperties} mapper for {@code fanar.*} YAML),
 * {@link qa.fanar.spring.boot.v4.FanarAutoConfiguration} (creates a
 * {@link qa.fanar.core.FanarClient} bean from the properties), and
 * {@link qa.fanar.spring.boot.v4.FanarHealthAutoConfiguration} (registers a
 * {@link qa.fanar.spring.boot.v4.FanarHealthIndicator} when {@code spring-boot-health} is on
 * the classpath, surfacing Fanar reachability in {@code /actuator/health}).</p>
 *
 * <p>This module does not ship a {@code module-info.java}: Spring Boot's auto-configuration and
 * Actuator wiring run on the classpath, and most Spring Framework jars don't declare full JPMS
 * modules. Forcing a JPMS module here would push that requirement down to consuming apps without
 * benefit. The jar still produces an automatic module name (from its artifact id) for projects
 * that do use the module path.</p>
 *
 * @author Oussama Mahjoub
 */
package qa.fanar.spring.boot.v4;
