/**
 * OpenTelemetry binding for the Fanar Java SDK's {@code ObservabilityPlugin} SPI.
 *
 * <p>Public type: {@link qa.fanar.obs.otel.OpenTelemetryObservabilityPlugin}. Wire it via
 * {@code FanarClient.builder().observability(new OpenTelemetryObservabilityPlugin(otel)).build()}
 * where {@code otel} is the application's configured {@code OpenTelemetry} instance (typically
 * {@code GlobalOpenTelemetry.get()} or a Spring/Quarkus-managed bean).</p>
 *
 * <p>Distributed-trace context propagation is fully supported: {@code propagationHeaders()}
 * returns a W3C {@code traceparent} (and {@code tracestate} if configured) so Fanar requests
 * stitch into the caller's trace at the server side, when Fanar exposes server spans.</p>
 *
 * <p>This module does not ship a {@code ServiceLoader} descriptor — observability is opt-in by
 * design, so adding the jar to the classpath alone does not change the {@code FanarClient}
 * default of {@link qa.fanar.core.spi.ObservabilityPlugin#noop()}.</p>
 */
package qa.fanar.obs.otel;
