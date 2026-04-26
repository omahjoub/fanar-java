/**
 * Micrometer Observation API binding for the Fanar Java SDK's {@code ObservabilityPlugin} SPI.
 *
 * <p>Public type: {@link qa.fanar.obs.micrometer.MicrometerObservabilityPlugin}. Wire it via
 * {@code FanarClient.builder().observability(new MicrometerObservabilityPlugin(registry)).build()}
 * where {@code registry} is the application's configured {@code ObservationRegistry} (typically
 * Spring Boot's auto-configured {@code observationRegistry} bean).</p>
 *
 * <p>This module does not ship a {@code ServiceLoader} descriptor — observability is opt-in by
 * design, so adding the jar to the classpath alone does not change the {@code FanarClient}
 * default of {@link qa.fanar.core.spi.ObservabilityPlugin#noop()}.</p>
 *
 * @author Oussama Mahjoub
 */
package qa.fanar.obs.micrometer;
