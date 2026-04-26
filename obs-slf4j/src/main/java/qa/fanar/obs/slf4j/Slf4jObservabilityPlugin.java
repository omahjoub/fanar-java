package qa.fanar.obs.slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

/**
 * {@link ObservabilityPlugin} that emits one structured log line per SDK operation through
 * SLF4J. {@code DEBUG} on success, {@code ERROR} on failure; the line carries the operation
 * name (in the logger), duration in milliseconds, and every attribute the SDK attached.
 *
 * <p>Logger names mirror the SDK's operation namespace verbatim, so configuration scopes
 * naturally:</p>
 * <pre>{@code
 * <logger name="fanar"             level="DEBUG"/>   <!-- everything -->
 * <logger name="fanar.audio"       level="DEBUG"/>   <!-- audio domain only -->
 * <logger name="fanar.audio.speech" level="OFF"/>    <!-- silence one operation -->
 * }</pre>
 *
 * <h2>Customizing what is logged</h2>
 *
 * <p>The default no-arg constructor logs every attribute the SDK emits with no transformation.
 * For non-default behavior, use the {@linkplain #builder() builder}:</p>
 *
 * <pre>{@code
 * ObservabilityPlugin obs = Slf4jObservabilityPlugin.builder()
 *         .attributeFilter(key -> !key.startsWith("internal."))      // drop internal.* keys
 *         .attributeRedactor((k, v) -> "fanar.user_id".equals(k) ? "***" : v)
 *         .build();
 * }</pre>
 *
 * <p>Both knobs apply on the close-summary line; raw attribute state is preserved internally so
 * the SDK's own attribute calls remain unaffected.</p>
 *
 * <p>Thread-safe: a single plugin instance backs every concurrent operation on a
 * {@code FanarClient}. Attributes are stored in a concurrent map; close is idempotent.</p>
 *
 * <p>Distributed-trace context propagation is <em>not</em> performed by this plugin —
 * {@link ObservationHandle#propagationHeaders()} returns an empty map. Use the OpenTelemetry
 * binding for trace context.</p>
 *
 * @author Oussama Mahjoub
 */
public final class Slf4jObservabilityPlugin implements ObservabilityPlugin {

    private static final Predicate<String> INCLUDE_ALL = key -> true;
    private static final BiFunction<String, Object, Object> IDENTITY = (k, v) -> v;

    private final Function<String, Logger> loggerFactory;
    private final Predicate<String> attributeFilter;
    private final BiFunction<String, Object, Object> attributeRedactor;

    /** Construct with the standard {@link LoggerFactory} and no attribute filtering or redaction. */
    public Slf4jObservabilityPlugin() {
        this(LoggerFactory::getLogger, INCLUDE_ALL, IDENTITY);
    }

    /**
     * Construct with a custom logger factory and default filter / redactor. Package-private
     * and intended for tests; production callers use the {@linkplain #Slf4jObservabilityPlugin()
     * no-arg constructor} or the {@linkplain #builder() builder}.
     */
    Slf4jObservabilityPlugin(Function<String, Logger> loggerFactory) {
        this(loggerFactory, INCLUDE_ALL, IDENTITY);
    }

    /**
     * Full-control constructor used by {@link Builder#build()} and tests that need to combine a
     * custom logger factory with a non-default filter or redactor.
     */
    Slf4jObservabilityPlugin(
            Function<String, Logger> loggerFactory,
            Predicate<String> attributeFilter,
            BiFunction<String, Object, Object> attributeRedactor) {
        this.loggerFactory = Objects.requireNonNull(loggerFactory, "loggerFactory");
        this.attributeFilter = Objects.requireNonNull(attributeFilter, "attributeFilter");
        this.attributeRedactor = Objects.requireNonNull(attributeRedactor, "attributeRedactor");
    }

    @Override
    public ObservationHandle start(String operationName) {
        Objects.requireNonNull(operationName, "operationName");
        Logger logger = loggerFactory.apply(operationName);
        return new Slf4jObservationHandle(
                logger, loggerFactory, attributeFilter, attributeRedactor,
                operationName, System.nanoTime());
    }

    /** Begin building a customized plugin. Defaults match the no-arg constructor. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link Slf4jObservabilityPlugin}. Customizations apply to the
     * close-summary line only; raw attribute state observed by the SDK is unaffected.
     */
    public static final class Builder {

        private Predicate<String> attributeFilter = INCLUDE_ALL;
        private BiFunction<String, Object, Object> attributeRedactor = IDENTITY;

        private Builder() { }

        /**
         * Set a predicate deciding which attribute keys are included in the close-summary line.
         * Returning {@code true} keeps the entry; {@code false} drops it. Default: keep every key.
         *
         * @param filter the predicate; must not be {@code null}
         */
        public Builder attributeFilter(Predicate<String> filter) {
            this.attributeFilter = Objects.requireNonNull(filter, "attributeFilter");
            return this;
        }

        /**
         * Set a per-attribute value transformer. Receives {@code (key, value)} and returns the
         * value to log — typically used to redact sensitive values to {@code "***"} or hash them.
         * Default: identity (log the raw value).
         *
         * @param redactor the transformer; must not be {@code null}. May return {@code null} to
         *                 log a literal {@code null} value.
         */
        public Builder attributeRedactor(BiFunction<String, Object, Object> redactor) {
            this.attributeRedactor = Objects.requireNonNull(redactor, "attributeRedactor");
            return this;
        }

        /** Build the plugin. */
        public Slf4jObservabilityPlugin build() {
            return new Slf4jObservabilityPlugin(
                    LoggerFactory::getLogger, attributeFilter, attributeRedactor);
        }
    }

    private static final class Slf4jObservationHandle implements ObservationHandle {

        private final Logger logger;
        private final Function<String, Logger> loggerFactory;
        private final Predicate<String> attributeFilter;
        private final BiFunction<String, Object, Object> attributeRedactor;
        private final String operationName;
        private final long startNanos;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Throwable error;

        Slf4jObservationHandle(
                Logger logger,
                Function<String, Logger> loggerFactory,
                Predicate<String> attributeFilter,
                BiFunction<String, Object, Object> attributeRedactor,
                String operationName,
                long startNanos) {
            this.logger = logger;
            this.loggerFactory = loggerFactory;
            this.attributeFilter = attributeFilter;
            this.attributeRedactor = attributeRedactor;
            this.operationName = operationName;
            this.startNanos = startNanos;
        }

        @Override
        public ObservationHandle attribute(String key, Object value) {
            Objects.requireNonNull(key, "key");
            if (value == null) {
                attributes.remove(key);
            } else {
                attributes.put(key, value);
            }
            return this;
        }

        @Override
        public ObservationHandle event(String name) {
            Objects.requireNonNull(name, "name");
            if (logger.isDebugEnabled()) {
                logger.debug("event={}", name);
            }
            return this;
        }

        @Override
        public ObservationHandle error(Throwable error) {
            Objects.requireNonNull(error, "error");
            this.error = error;
            return this;
        }

        @Override
        public ObservationHandle child(String operationName) {
            Objects.requireNonNull(operationName, "operationName");
            String childName = this.operationName + "." + operationName;
            Logger childLogger = loggerFactory.apply(childName);
            return new Slf4jObservationHandle(
                    childLogger, loggerFactory, attributeFilter, attributeRedactor,
                    childName, System.nanoTime());
        }

        @Override
        public Map<String, String> propagationHeaders() {
            // SLF4J does not carry distributed-trace context.
            return Map.of();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            Throwable failure = this.error;
            Map<String, Object> projected = projectAttributes();
            if (failure != null) {
                if (logger.isErrorEnabled()) {
                    logger.error("failed after {}ms attrs={}", durationMs, projected, failure);
                }
            } else if (logger.isDebugEnabled()) {
                logger.debug("ok in {}ms attrs={}", durationMs, projected);
            }
        }

        /**
         * Apply {@link #attributeFilter} and {@link #attributeRedactor} to the raw attribute map
         * for the close-summary log line.
         */
        private Map<String, Object> projectAttributes() {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : attributes.entrySet()) {
                if (attributeFilter.test(e.getKey())) {
                    out.put(e.getKey(), attributeRedactor.apply(e.getKey(), e.getValue()));
                }
            }
            return out;
        }
    }
}
