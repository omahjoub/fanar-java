package qa.fanar.core.spi;

import qa.fanar.core.internal.observability.NoopObservabilityPlugin;

/**
 * Observability contract for the SDK's metrics and tracing.
 *
 * <p>One plugin per {@code FanarClient}. The SDK opens one observation per semantic operation
 * (for example {@code fanar.chat}, {@code fanar.audio.speech}) and attaches standardized
 * attributes defined in {@link FanarObservationAttributes}. Downstream adapters (Micrometer,
 * OpenTelemetry, an in-memory test double, a simple logger) implement this interface and bind
 * the observations to their respective backends.</p>
 *
 * <p>Implementations must be thread-safe. The SDK may call {@link #start(String)} concurrently
 * for overlapping operations against a single plugin instance.</p>
 *
 * <p>The default implementation ({@link #noop()}) is silent — every {@link ObservationHandle}
 * method is a no-op. It is the plugin used when the user has not installed one explicitly and
 * ensures the SDK's internal {@code observation().event(...)} calls are always safe.</p>
 */
public interface ObservabilityPlugin {

    /**
     * Begin an observation for a named semantic operation. The returned handle is
     * {@link AutoCloseable} and must be closed (typically via try-with-resources) to end the
     * observation.
     *
     * @param operationName a stable name identifying the operation, e.g. {@code fanar.chat}.
     *                      Must not be {@code null}.
     * @return a handle bound to this observation; never {@code null}
     */
    ObservationHandle start(String operationName);

    /**
     * The silent default plugin. Every call on the returned plugin is a no-op; observation
     * handles ignore attribute, event, error, and child calls; {@code propagationHeaders}
     * returns an empty map; {@code close} does nothing.
     *
     * <p>Thread-safe and allocation-free after first load: the same instance is returned on
     * every call.</p>
     *
     * @return the singleton no-op plugin
     */
    static ObservabilityPlugin noop() {
        return NoopObservabilityPlugin.INSTANCE;
    }
}
