/**
 * Micrometer Observation API binding for the Fanar Java SDK's {@code ObservabilityPlugin} SPI.
 *
 * <p>Opens one {@code io.micrometer.observation.Observation} per logical SDK operation (for
 * example {@code fanar.chat.send}, {@code fanar.audio.speech}). The consuming application wires
 * whatever Micrometer handlers it cares about — {@code ObservationToMetricsHandler} for
 * Prometheus / Datadog metrics, {@code micrometer-tracing-bridge-*} for distributed tracing —
 * and the same SDK observations flow into all of them.</p>
 *
 * <p>Distributed-trace context propagation is <em>not</em> performed directly by this binding —
 * {@code propagationHeaders()} returns an empty map. If a tracing bridge (e.g.,
 * {@code micrometer-tracing-bridge-otel}) is wired in the consuming application, it updates the
 * tracer's context internally but does not surface back through this SPI. To inject
 * {@code traceparent} into outbound Fanar requests, compose with the {@code obs-otel} adapter.</p>
 *
 * @author Oussama Mahjoub
 */
module qa.fanar.obs.micrometer {
    requires qa.fanar.core;
    requires micrometer.observation;

    exports qa.fanar.obs.micrometer;
}
