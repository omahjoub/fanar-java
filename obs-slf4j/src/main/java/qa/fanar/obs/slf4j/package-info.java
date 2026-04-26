/**
 * SLF4J binding for the Fanar Java SDK's {@code ObservabilityPlugin} SPI.
 *
 * <p>Public type: {@link qa.fanar.obs.slf4j.Slf4jObservabilityPlugin}. Wire it via
 * {@code FanarClient.builder().observability(new Slf4jObservabilityPlugin()).build()}. The
 * plugin emits one log line per SDK operation through SLF4J — {@code DEBUG} on success,
 * {@code ERROR} on failure — and is fully transparent on threads (idempotent close, concurrent
 * attribute updates).</p>
 *
 * <p>Unlike the JSON codec adapters this module does <em>not</em> ship a {@code ServiceLoader}
 * descriptor: observability is opt-in by design, so adding the jar to the classpath alone does
 * not change the {@code FanarClient} default of
 * {@link qa.fanar.core.spi.ObservabilityPlugin#noop()}.</p>
 */
package qa.fanar.obs.slf4j;
