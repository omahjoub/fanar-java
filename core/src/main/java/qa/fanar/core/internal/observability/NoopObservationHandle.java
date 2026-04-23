package qa.fanar.core.internal.observability;

import java.util.Map;

import qa.fanar.core.spi.ObservationHandle;

/**
 * Silent {@link ObservationHandle} returned by {@link NoopObservabilityPlugin}.
 *
 * <p>Every method is a no-op: fluent methods return the singleton itself, {@code
 * propagationHeaders} returns an empty immutable map, {@code close} does nothing. Allocation-
 * free after first load.</p>
 *
 * <p>Internal implementation detail. Not part of the public API (ADR-018).</p>
 */
public final class NoopObservationHandle implements ObservationHandle {

    /** Process-wide singleton. */
    public static final NoopObservationHandle INSTANCE = new NoopObservationHandle();

    private NoopObservationHandle() {
        // singleton
    }

    @Override
    public ObservationHandle attribute(String key, Object value) {
        return this;
    }

    @Override
    public ObservationHandle event(String name) {
        return this;
    }

    @Override
    public ObservationHandle error(Throwable error) {
        return this;
    }

    @Override
    public ObservationHandle child(String operationName) {
        return this;
    }

    @Override
    public Map<String, String> propagationHeaders() {
        return Map.of();
    }

    @Override
    public void close() {
        // no-op
    }
}
