package qa.fanar.core.internal.observability;

import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

/**
 * Silent default {@link ObservabilityPlugin} used when the caller has not installed one.
 *
 * <p>Internal implementation detail accessed only via {@link ObservabilityPlugin#noop()}. Not
 * part of the public API — may be replaced, renamed, or deleted in any release (ADR-018).</p>
 */
public final class NoopObservabilityPlugin implements ObservabilityPlugin {

    /** Process-wide singleton. */
    public static final NoopObservabilityPlugin INSTANCE = new NoopObservabilityPlugin();

    private NoopObservabilityPlugin() {
        // singleton
    }

    @Override
    public ObservationHandle start(String operationName) {
        return NoopObservationHandle.INSTANCE;
    }
}
