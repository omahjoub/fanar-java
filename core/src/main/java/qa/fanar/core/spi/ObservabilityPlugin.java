package qa.fanar.core.spi;

import java.util.List;

import qa.fanar.core.internal.observability.CompositeObservabilityPlugin;
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
 *
 * @author Oussama Mahjoub
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

    /**
     * Compose multiple plugins into one. Every method call on the returned plugin (and on
     * handles it produces) is fanned out to each child plugin in order.
     *
     * <p>Use this to wire two or more observability backends behind a single
     * {@code FanarClient} — for example, an SLF4J adapter for log lines and an OpenTelemetry
     * adapter for spans + trace propagation. Each backend operates independently; they share
     * only the operation name, attributes, events, and error signal that the SDK emits.</p>
     *
     * <p>Optimizations: {@code compose()} with zero plugins returns {@link #noop()};
     * {@code compose(p)} with a single plugin returns {@code p} unwrapped (no composition
     * overhead). Otherwise a composite plugin is constructed.</p>
     *
     * <p>Behavior on {@link ObservationHandle#propagationHeaders()}: child handles' maps are
     * merged in the order plugins were supplied; on key collision the later plugin wins. In
     * practice only one tracing-aware plugin emits trace-context headers, so collisions are
     * rare.</p>
     *
     * <p>Behavior on exceptions: a child plugin that throws propagates the exception up through
     * the composite. Subsequent children are not invoked for that call. Wire defensively if a
     * particular backend may misbehave.</p>
     *
     * @param plugins the plugins to compose; must not be {@code null} and must not contain
     *                {@code null} elements
     * @return a plugin that fans out to all supplied plugins
     */
    static ObservabilityPlugin compose(ObservabilityPlugin... plugins) {
        return CompositeObservabilityPlugin.of(List.of(plugins));
    }

    /** {@link List}-accepting overload of {@link #compose(ObservabilityPlugin...)}. */
    static ObservabilityPlugin compose(List<ObservabilityPlugin> plugins) {
        return CompositeObservabilityPlugin.of(plugins);
    }
}
