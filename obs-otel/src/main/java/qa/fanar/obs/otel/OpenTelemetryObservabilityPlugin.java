package qa.fanar.obs.otel;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapSetter;

import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

/**
 * {@link ObservabilityPlugin} that opens one OpenTelemetry span per SDK operation.
 *
 * <p>Each {@link #start(String)} call creates a span via the supplied {@link OpenTelemetry}'s
 * {@link Tracer}. Attributes attached through {@link ObservationHandle#attribute} are mapped to
 * the right typed {@code Span.setAttribute} overload (long / double / boolean / String);
 * {@link ObservationHandle#event} becomes a span event; {@link ObservationHandle#error} records
 * the exception and sets the span status to {@link StatusCode#ERROR}; {@link ObservationHandle#close}
 * ends the span. Child observations create child spans whose parent is the captured context, so
 * parent-child relationships hold even when the SDK hops threads (virtual-thread async paths).</p>
 *
 * <h2>Distributed-trace context propagation</h2>
 *
 * <p>{@link ObservationHandle#propagationHeaders()} injects the W3C {@code traceparent} (and
 * {@code tracestate} if configured) into a fresh map via the {@link OpenTelemetry}'s configured
 * {@link ContextPropagators}. The SDK merges these headers into the outbound HTTP request, so
 * the Fanar server's logs / spans (when exposed) link back to the calling application's trace.</p>
 *
 * <h2>Customizing what is logged</h2>
 *
 * <pre>{@code
 * ObservabilityPlugin obs = OpenTelemetryObservabilityPlugin.builder(otel)
 *         .instrumentationName("my-app")          // default: "qa.fanar.obs.otel"
 *         .instrumentationVersion("1.0.0")        // default: null
 *         .attributeFilter(key -> !key.startsWith("internal."))
 *         .attributeRedactor((k, v) -> "fanar.user_id".equals(k) ? "***" : v)
 *         .build();
 * }</pre>
 *
 * <p>Thread-safe: a single plugin instance backs every concurrent operation on a
 * {@code FanarClient}. Span lifecycle is per-handle; close is idempotent.</p>
 *
 * @author Oussama Mahjoub
 */
public final class OpenTelemetryObservabilityPlugin implements ObservabilityPlugin {

    private static final String DEFAULT_INSTRUMENTATION_NAME = "qa.fanar.obs.otel";
    private static final Predicate<String> INCLUDE_ALL = key -> true;
    private static final BiFunction<String, Object, Object> IDENTITY = (k, v) -> v;
    private static final TextMapSetter<Map<String, String>> MAP_SETTER = Map::put;

    private final Tracer tracer;
    private final ContextPropagators propagators;
    private final Predicate<String> attributeFilter;
    private final BiFunction<String, Object, Object> attributeRedactor;

    /**
     * Construct from an {@link OpenTelemetry} instance with default instrumentation name and
     * no attribute filtering or redaction. Equivalent to
     * {@code builder(openTelemetry).build()}.
     */
    public OpenTelemetryObservabilityPlugin(OpenTelemetry openTelemetry) {
        this(builder(openTelemetry));
    }

    private OpenTelemetryObservabilityPlugin(Builder b) {
        Objects.requireNonNull(b.openTelemetry, "openTelemetry");
        this.tracer = b.instrumentationVersion == null
                ? b.openTelemetry.getTracer(b.instrumentationName)
                : b.openTelemetry.getTracer(b.instrumentationName, b.instrumentationVersion);
        this.propagators = b.openTelemetry.getPropagators();
        this.attributeFilter = b.attributeFilter;
        this.attributeRedactor = b.attributeRedactor;
    }

    @Override
    public ObservationHandle start(String operationName) {
        Objects.requireNonNull(operationName, "operationName");
        Span span = tracer.spanBuilder(operationName).startSpan();
        Context context = Context.current().with(span);
        return new OtelObservationHandle(
                tracer, propagators, span, context, attributeFilter, attributeRedactor);
    }

    /** Begin building a customized plugin. */
    public static Builder builder(OpenTelemetry openTelemetry) {
        return new Builder(openTelemetry);
    }

    /**
     * Fluent builder for {@link OpenTelemetryObservabilityPlugin}. Customizations apply at span
     * construction (instrumentation identity) and to attribute calls (filter / redactor).
     */
    public static final class Builder {

        private final OpenTelemetry openTelemetry;
        private String instrumentationName = DEFAULT_INSTRUMENTATION_NAME;
        private String instrumentationVersion;
        private Predicate<String> attributeFilter = INCLUDE_ALL;
        private BiFunction<String, Object, Object> attributeRedactor = IDENTITY;

        private Builder(OpenTelemetry openTelemetry) {
            this.openTelemetry = Objects.requireNonNull(openTelemetry, "openTelemetry");
        }

        /**
         * Set the instrumentation library name passed to {@code OpenTelemetry.getTracer(name)}.
         * Default {@code "qa.fanar.obs.otel"}.
         */
        public Builder instrumentationName(String name) {
            this.instrumentationName = Objects.requireNonNull(name, "instrumentationName");
            return this;
        }

        /**
         * Set the instrumentation library version. Default {@code null} (omitted from the tracer).
         */
        public Builder instrumentationVersion(String version) {
            this.instrumentationVersion = version;
            return this;
        }

        /**
         * Set a predicate deciding which attribute keys reach the span. Returning {@code true}
         * keeps the entry; {@code false} drops it before {@code Span.setAttribute} is called.
         * Default: keep every key.
         */
        public Builder attributeFilter(Predicate<String> filter) {
            this.attributeFilter = Objects.requireNonNull(filter, "attributeFilter");
            return this;
        }

        /**
         * Set a per-attribute value transformer. Receives {@code (key, value)} and returns the
         * value to record on the span. Default: identity.
         */
        public Builder attributeRedactor(BiFunction<String, Object, Object> redactor) {
            this.attributeRedactor = Objects.requireNonNull(redactor, "attributeRedactor");
            return this;
        }

        /** Build the plugin. */
        public OpenTelemetryObservabilityPlugin build() {
            return new OpenTelemetryObservabilityPlugin(this);
        }
    }

    private static final class OtelObservationHandle implements ObservationHandle {

        private final Tracer tracer;
        private final ContextPropagators propagators;
        private final Span span;
        private final Context context;
        private final Predicate<String> attributeFilter;
        private final BiFunction<String, Object, Object> attributeRedactor;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Throwable error;

        OtelObservationHandle(
                Tracer tracer,
                ContextPropagators propagators,
                Span span,
                Context context,
                Predicate<String> attributeFilter,
                BiFunction<String, Object, Object> attributeRedactor) {
            this.tracer = tracer;
            this.propagators = propagators;
            this.span = span;
            this.context = context;
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
            switch (redacted) {
                case String s -> span.setAttribute(key, s);
                case Long l -> span.setAttribute(key, l);
                case Integer i -> span.setAttribute(key, i.longValue());
                case Double d -> span.setAttribute(key, d);
                case Float f -> span.setAttribute(key, f.doubleValue());
                case Boolean b -> span.setAttribute(key, b);
                default -> span.setAttribute(key, redacted.toString());
            }
            return this;
        }

        @Override
        public ObservationHandle event(String name) {
            Objects.requireNonNull(name, "name");
            span.addEvent(name);
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
            // Use the captured parent context explicitly so parent-child stitches even when
            // the child is opened on a different thread (e.g., a virtual-thread async hop).
            Span childSpan = tracer.spanBuilder(operationName).setParent(context).startSpan();
            Context childContext = context.with(childSpan);
            return new OtelObservationHandle(
                    tracer, propagators, childSpan, childContext,
                    attributeFilter, attributeRedactor);
        }

        @Override
        public Map<String, String> propagationHeaders() {
            Map<String, String> headers = new HashMap<>();
            propagators.getTextMapPropagator().inject(context, headers, MAP_SETTER);
            return headers;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Throwable failure = this.error;
            if (failure != null) {
                span.recordException(failure);
                span.setStatus(StatusCode.ERROR, failure.getMessage() == null ? "" : failure.getMessage());
            }
            span.end();
        }
    }
}
