/**
 * OpenTelemetry binding for the Fanar Java SDK's {@code ObservabilityPlugin} SPI.
 *
 * <p>Opens one span per logical SDK operation (for example {@code fanar.chat.send},
 * {@code fanar.audio.speech}), maps {@code ObservationHandle.attribute(...)} to the right typed
 * {@code Span.setAttribute} overload, and injects W3C trace-context headers via
 * {@code ObservationHandle.propagationHeaders()} so Fanar requests stitch into the caller's
 * distributed trace.</p>
 *
 * @author Oussama Mahjoub
 */
module qa.fanar.obs.otel {
    requires qa.fanar.core;
    requires io.opentelemetry.api;
    requires io.opentelemetry.context;

    exports qa.fanar.obs.otel;
}
