package qa.fanar.obs.micrometer;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

/**
 * {@link ObservabilityPlugin} that opens one Micrometer {@link Observation} per SDK operation.
 *
 * <p>Each {@link #start(String)} call creates an Observation via the supplied
 * {@link ObservationRegistry}. Attributes attached through {@link ObservationHandle#attribute}
 * become {@linkplain Observation#lowCardinalityKeyValue low-cardinality} {@code KeyValue}s on
 * the Observation (suitable for metric tags); {@link ObservationHandle#event} becomes a
 * {@link Observation.Event}; {@link ObservationHandle#error} records the throwable;
 * {@link ObservationHandle#close} stops the Observation. Child observations are explicit
 * children of the captured parent context.</p>
 *
 * <h2>Backends</h2>
 *
 * <p>This binding does not produce metrics or trace spans on its own — it only emits Observations.
 * The downstream effect depends on which Micrometer handlers the consuming application registers
 * on its {@code ObservationRegistry}:</p>
 * <ul>
 *   <li>{@code DefaultMeterObservationHandler} (Spring Boot auto-configures this when a
 *       {@code MeterRegistry} bean is present) — Observations become {@code Timer} samples for
 *       Prometheus, Datadog, JMX, etc.</li>
 *   <li>{@code micrometer-tracing-bridge-otel} or {@code -brave} — Observations become spans on
 *       the bridged tracer.</li>
 *   <li>Custom {@code ObservationHandler}s — application-defined behavior.</li>
 * </ul>
 *
 * <h2>Trace context propagation</h2>
 *
 * <p>{@link ObservationHandle#propagationHeaders()} returns an empty map. Micrometer's tracing
 * bridges update the bridged tracer's context internally but don't surface trace headers back
 * through this SPI. To inject {@code traceparent} into outbound Fanar requests, use the
 * {@code obs-otel} adapter directly or compose:</p>
 * <pre>{@code
 * client.observability(ObservabilityPlugin.compose(
 *         new MicrometerObservabilityPlugin(registry),
 *         new OpenTelemetryObservabilityPlugin(otel)));
 * }</pre>
 *
 * <h2>Customizing what is recorded</h2>
 *
 * <pre>{@code
 * ObservabilityPlugin obs = MicrometerObservabilityPlugin.builder(registry)
 *         .attributeFilter(key -> !key.startsWith("internal."))
 *         .attributeRedactor((k, v) -> "fanar.user_id".equals(k) ? "***" : v)
 *         .build();
 * }</pre>
 *
 * <p>Thread-safe: a single plugin instance backs every concurrent operation on a
 * {@code FanarClient}. Observation lifecycle is per-handle; close is idempotent.</p>
 */
public final class MicrometerObservabilityPlugin implements ObservabilityPlugin {

    private static final Predicate<String> INCLUDE_ALL = key -> true;
    private static final BiFunction<String, Object, Object> IDENTITY = (k, v) -> v;

    private final ObservationRegistry registry;
    private final Predicate<String> attributeFilter;
    private final BiFunction<String, Object, Object> attributeRedactor;

    /**
     * Construct from an {@link ObservationRegistry} with no attribute filtering or redaction.
     * Equivalent to {@code builder(registry).build()}.
     */
    public MicrometerObservabilityPlugin(ObservationRegistry registry) {
        this(builder(registry));
    }

    private MicrometerObservabilityPlugin(Builder b) {
        this.registry = Objects.requireNonNull(b.registry, "registry");
        this.attributeFilter = b.attributeFilter;
        this.attributeRedactor = b.attributeRedactor;
    }

    @Override
    public ObservationHandle start(String operationName) {
        Objects.requireNonNull(operationName, "operationName");
        Observation observation = Observation.start(operationName, registry);
        return new MicrometerObservationHandle(
                registry, observation, attributeFilter, attributeRedactor);
    }

    /** Begin building a customized plugin. */
    public static Builder builder(ObservationRegistry registry) {
        return new Builder(registry);
    }

    /** Fluent builder for {@link MicrometerObservabilityPlugin}. */
    public static final class Builder {

        private final ObservationRegistry registry;
        private Predicate<String> attributeFilter = INCLUDE_ALL;
        private BiFunction<String, Object, Object> attributeRedactor = IDENTITY;

        private Builder(ObservationRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry");
        }

        /**
         * Set a predicate deciding which attribute keys reach the Observation. Returning
         * {@code true} keeps the entry; {@code false} drops it before
         * {@code Observation.lowCardinalityKeyValue} is called. Default: keep every key.
         */
        public Builder attributeFilter(Predicate<String> filter) {
            this.attributeFilter = Objects.requireNonNull(filter, "attributeFilter");
            return this;
        }

        /**
         * Set a per-attribute value transformer. Receives {@code (key, value)} and returns the
         * value to record on the Observation. Default: identity.
         */
        public Builder attributeRedactor(BiFunction<String, Object, Object> redactor) {
            this.attributeRedactor = Objects.requireNonNull(redactor, "attributeRedactor");
            return this;
        }

        /** Build the plugin. */
        public MicrometerObservabilityPlugin build() {
            return new MicrometerObservabilityPlugin(this);
        }
    }

    private static final class MicrometerObservationHandle implements ObservationHandle {

        private final ObservationRegistry registry;
        private final Observation observation;
        private final Predicate<String> attributeFilter;
        private final BiFunction<String, Object, Object> attributeRedactor;
        private final AtomicBoolean closed = new AtomicBoolean();

        MicrometerObservationHandle(
                ObservationRegistry registry,
                Observation observation,
                Predicate<String> attributeFilter,
                BiFunction<String, Object, Object> attributeRedactor) {
            this.registry = registry;
            this.observation = observation;
            this.attributeFilter = attributeFilter;
            this.attributeRedactor = attributeRedactor;
        }

        @Override
        public ObservationHandle attribute(String key, Object value) {
            Objects.requireNonNull(key, "key");
            if (!attributeFilter.test(key)) {
                return this;
            }
            Object redacted = attributeRedactor.apply(key, value);
            if (redacted == null) {
                return this;
            }
            // Micrometer's metric path strongly prefers low-cardinality tags; the SDK's
            // standardized attributes (`http.*`, `fanar.*`) are bounded so this default is safe.
            // Users who want high-cardinality on specific keys can write a custom plugin.
            observation.lowCardinalityKeyValue(key, String.valueOf(redacted));
            return this;
        }

        @Override
        public ObservationHandle event(String name) {
            Objects.requireNonNull(name, "name");
            observation.event(Observation.Event.of(name));
            return this;
        }

        @Override
        public ObservationHandle error(Throwable error) {
            Objects.requireNonNull(error, "error");
            observation.error(error);
            return this;
        }

        @Override
        public ObservationHandle child(String operationName) {
            Objects.requireNonNull(operationName, "operationName");
            // Explicit parent so the relationship holds across thread hops (virtual-thread async).
            Observation childObservation = Observation.createNotStarted(operationName, registry)
                    .parentObservation(this.observation)
                    .start();
            return new MicrometerObservationHandle(
                    registry, childObservation, attributeFilter, attributeRedactor);
        }

        @Override
        public Map<String, String> propagationHeaders() {
            // Micrometer does not surface trace context as HTTP headers through this SPI.
            // To propagate, compose with the obs-otel adapter.
            return Map.of();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            observation.stop();
        }
    }
}
