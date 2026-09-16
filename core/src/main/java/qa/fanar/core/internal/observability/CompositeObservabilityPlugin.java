package qa.fanar.core.internal.observability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

/**
 * Fan-out {@link ObservabilityPlugin} produced by
 * {@link ObservabilityPlugin#compose(ObservabilityPlugin...)}. Internal implementation detail
 * (ADR-018) — callers construct it via the {@code compose(...)} factory, never directly.
 *
 * @author Oussama Mahjoub
 */
public final class CompositeObservabilityPlugin implements ObservabilityPlugin {

    private final List<ObservabilityPlugin> plugins;

    private CompositeObservabilityPlugin(List<ObservabilityPlugin> plugins) {
        this.plugins = plugins;
    }

    /**
     * Factory used by {@link ObservabilityPlugin#compose(ObservabilityPlugin...)}. Returns the
     * silent {@link ObservabilityPlugin#noop()} for an empty input and the original plugin
     * unwrapped when only one is supplied; otherwise returns a real composite.
     */
    public static ObservabilityPlugin of(List<ObservabilityPlugin> plugins) {
        Objects.requireNonNull(plugins, "plugins");
        List<ObservabilityPlugin> copy = new ArrayList<>(plugins.size());
        for (ObservabilityPlugin p : plugins) {
            copy.add(Objects.requireNonNull(p, "plugins must not contain null elements"));
        }
        if (copy.isEmpty()) {
            return ObservabilityPlugin.noop();
        }
        if (copy.size() == 1) {
            return copy.getFirst();
        }
        return new CompositeObservabilityPlugin(List.copyOf(copy));
    }

    @Override
    public ObservationHandle start(String operationName) {
        Objects.requireNonNull(operationName, "operationName");
        List<ObservationHandle> handles = new ArrayList<>(plugins.size());
        for (ObservabilityPlugin p : plugins) {
            ObservationHandle h;
            try {
                h = p.start(operationName);
            } catch (RuntimeException e) {
                h = null;
            }
            // A plugin that failed to start, or returned null, still occupies a slot: the silent
            // handle keeps the fan-out total and every later call safe.
            handles.add(h == null ? NoopObservationHandle.INSTANCE : h);
        }
        return new CompositeObservationHandle(handles);
    }

    /**
     * Run one child's call, absorbing a {@link RuntimeException} it throws.
     *
     * <p>Telemetry is not worth a failed request: one misbehaving backend must neither break the
     * caller's call nor stop its siblings observing (ADR-022). {@link Error} is not caught — it is
     * not the plugin's to recover from. Core carries no logger (zero runtime dependencies,
     * ADR-002), so the failure is dropped here; a plugin that wants its own faults visible reports
     * them itself.</p>
     */
    private static void isolate(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException ignored) {
            // Deliberately swallowed — see above.
        }
    }

    private static final class CompositeObservationHandle implements ObservationHandle {

        private final List<ObservationHandle> handles;
        private final AtomicBoolean closed = new AtomicBoolean();

        CompositeObservationHandle(List<ObservationHandle> handles) {
            this.handles = handles;
        }

        @Override
        public ObservationHandle attribute(String key, Object value) {
            Objects.requireNonNull(key, "key");
            for (ObservationHandle h : handles) {
                isolate(() -> h.attribute(key, value));
            }
            return this;
        }

        @Override
        public ObservationHandle event(String name) {
            Objects.requireNonNull(name, "name");
            for (ObservationHandle h : handles) {
                isolate(() -> h.event(name));
            }
            return this;
        }

        @Override
        public ObservationHandle error(Throwable error) {
            Objects.requireNonNull(error, "error");
            for (ObservationHandle h : handles) {
                isolate(() -> h.error(error));
            }
            return this;
        }

        @Override
        public ObservationHandle child(String operationName) {
            Objects.requireNonNull(operationName, "operationName");
            List<ObservationHandle> children = new ArrayList<>(handles.size());
            for (ObservationHandle h : handles) {
                ObservationHandle c;
                try {
                    c = h.child(operationName);
                } catch (RuntimeException e) {
                    c = null;
                }
                children.add(c == null ? NoopObservationHandle.INSTANCE : c);
            }
            return new CompositeObservationHandle(children);
        }

        @Override
        public Map<String, String> propagationHeaders() {
            // Merge in order; on key collision the later plugin wins. Typical case is a single
            // tracing-aware plugin contributing all headers and others returning empty maps.
            Map<String, String> merged = new LinkedHashMap<>();
            for (ObservationHandle h : handles) {
                isolate(() -> merged.putAll(h.propagationHeaders()));
            }
            return merged;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            for (ObservationHandle h : handles) {
                isolate(h::close);
            }
        }
    }
}
