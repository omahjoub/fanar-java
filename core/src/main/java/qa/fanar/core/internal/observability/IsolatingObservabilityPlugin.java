package qa.fanar.core.internal.observability;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

/**
 * Wraps the client's {@link ObservabilityPlugin} so that no failure inside it can reach the caller.
 *
 * <p><strong>Telemetry must never fail a request.</strong> The plugins users actually install are
 * adapters over networked backends — an OpenTelemetry exporter whose queue is full, a Micrometer
 * registry rejecting a duplicate meter name — and "must not throw" is not a promise those backends
 * can keep. Without this wrapper a plugin has one opportunity per SPI call, dozens per request, to
 * turn a successful Fanar call into an exception the caller never asked for.</p>
 *
 * <p>Applied once, in the {@code FanarClient} constructor, so the guarantee holds however the
 * plugin was supplied — directly, or through {@link ObservabilityPlugin#compose}. That is a
 * different concern from the fan-out isolation in {@link CompositeObservabilityPlugin}, which stops
 * one composed plugin's failure from blinding its siblings; this one stops any plugin's failure
 * from reaching the request. Both are needed, and neither implies the other.</p>
 *
 * <p>{@link Error} is not caught — an {@code OutOfMemoryError} is not the plugin's to recover from,
 * and hiding it would suppress a failure the application needs to see.</p>
 *
 * <p>Failures are not silent. The first from a given plugin is reported at
 * {@link Level#WARNING} through {@link System#getLogger}, which is JDK-built-in and so costs core no
 * dependency (ADR-002); later ones drop to {@link Level#DEBUG}, because a plugin that throws once
 * usually throws on every call and a per-call warning would bury the first one.</p>
 *
 * <p>Internal (ADR-018).</p>
 *
 * @author Oussama Mahjoub
 */
public final class IsolatingObservabilityPlugin implements ObservabilityPlugin {

    private static final Logger LOG = System.getLogger("qa.fanar.core.observability");

    private final ObservabilityPlugin delegate;
    private final AtomicBoolean reported = new AtomicBoolean();

    private IsolatingObservabilityPlugin(ObservabilityPlugin delegate) {
        this.delegate = delegate;
    }

    /**
     * Wrap a plugin so its failures cannot reach the caller.
     *
     * @param plugin the plugin to guard; must not be {@code null}
     * @return the guarded plugin, or {@code plugin} itself when it is the silent no-op, which has
     *         nothing to guard against
     */
    public static ObservabilityPlugin wrap(ObservabilityPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        if (plugin == NoopObservabilityPlugin.INSTANCE) {
            return plugin;
        }
        return new IsolatingObservabilityPlugin(plugin);
    }

    @Override
    public ObservationHandle start(String operationName) {
        Objects.requireNonNull(operationName, "operationName");
        ObservationHandle started;
        try {
            started = delegate.start(operationName);
        } catch (RuntimeException e) {
            report("start", e);
            started = null;
        }
        // A plugin that failed to start, or returned null, still owes the caller a usable handle.
        return started == null ? NoopObservationHandle.INSTANCE : new IsolatingHandle(started);
    }

    private void report(String method, RuntimeException failure) {
        String message = "Fanar observability plugin " + delegate.getClass().getName()
                + " threw from " + method + "(); the call itself is unaffected";
        if (reported.compareAndSet(false, true)) {
            LOG.log(Level.WARNING, message + ". Further failures from this plugin log at DEBUG.",
                    failure);
        } else {
            LOG.log(Level.DEBUG, message, failure);
        }
    }

    /** Guards one observation's lifecycle, reporting through the owning plugin's rate limit. */
    private final class IsolatingHandle implements ObservationHandle {

        private final ObservationHandle delegateHandle;

        IsolatingHandle(ObservationHandle delegateHandle) {
            this.delegateHandle = delegateHandle;
        }

        @Override
        public ObservationHandle attribute(String key, Object value) {
            try {
                delegateHandle.attribute(key, value);
            } catch (RuntimeException e) {
                report("attribute", e);
            }
            // Always this handle, never the delegate's return: chaining must stay guarded.
            return this;
        }

        @Override
        public ObservationHandle event(String name) {
            try {
                delegateHandle.event(name);
            } catch (RuntimeException e) {
                report("event", e);
            }
            return this;
        }

        @Override
        public ObservationHandle error(Throwable error) {
            try {
                delegateHandle.error(error);
            } catch (RuntimeException e) {
                report("error", e);
            }
            return this;
        }

        @Override
        public ObservationHandle child(String operationName) {
            ObservationHandle childHandle;
            try {
                childHandle = delegateHandle.child(operationName);
            } catch (RuntimeException e) {
                report("child", e);
                childHandle = null;
            }
            return childHandle == null
                    ? NoopObservationHandle.INSTANCE
                    : new IsolatingHandle(childHandle);
        }

        @Override
        public Map<String, String> propagationHeaders() {
            try {
                Map<String, String> headers = delegateHandle.propagationHeaders();
                // A null here would NPE the caller merging it into the request.
                return headers == null ? Map.of() : headers;
            } catch (RuntimeException e) {
                report("propagationHeaders", e);
                return Map.of();
            }
        }

        @Override
        public void close() {
            try {
                delegateHandle.close();
            } catch (RuntimeException e) {
                report("close", e);
            }
        }
    }
}
