package qa.fanar.e2e;

import java.time.Duration;
import java.util.Optional;

import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

import qa.fanar.core.FanarClient;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.interceptor.logging.WireLoggingInterceptor;
import qa.fanar.obs.micrometer.MicrometerObservabilityPlugin;
import qa.fanar.obs.otel.OpenTelemetryObservabilityPlugin;
import qa.fanar.obs.slf4j.Slf4jObservabilityPlugin;

/**
 * Factory methods for building {@link FanarClient} instances used by e2e tests.
 *
 * <p>The canonical API key lookup is the {@code FANAR_API_KEY} environment variable — the same
 * one the SDK itself honours via {@code FanarClient.ENV_API_KEY}. E2e tests do not accept keys
 * from properties files, JVM system properties, or anything else; see
 * {@link #apiKey()} for the resolver.</p>
 *
 * <p>Live tests should annotate themselves with
 * {@code @EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")} and
 * {@code @Tag("live")} so they are skipped when no key is present and can be filtered via
 * the {@code groups} / {@code excludedGroups} Surefire options.</p>
 */
public final class TestClients {

    private TestClients() {
        // not instantiable
    }

    /** Resolve the Fanar API key from the environment, or empty if unset/blank. */
    public static Optional<String> apiKey() {
        String value = System.getenv(FanarClient.ENV_API_KEY);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /**
     * A {@link FanarClient} configured with the provided codec and a generous per-request
     * timeout appropriate for streaming tests against the live API. Caller closes the client
     * (try-with-resources).
     *
     * @throws IllegalStateException if {@code FANAR_API_KEY} is not set
     */
    public static FanarClient live(FanarJsonCodec codec) {
        return liveBuilder(codec).build();
    }

    /**
     * Same as {@link #live(FanarJsonCodec)} but with four diagnostics turned on:
     * <ul>
     *   <li>{@link WireLoggingInterceptor} at {@link WireLoggingInterceptor.Level#BODY} — prints
     *       every request and response (method, URL, headers, body) through SLF4J. The e2e
     *       module's {@code simplelogger.properties} routes the {@code fanar.wire} logger to
     *       {@code stderr} at {@code DEBUG}.</li>
     *   <li>{@link Slf4jObservabilityPlugin} — emits one structured log line per SDK operation
     *       through SLF4J. Same routing on the {@code fanar.*} namespace.</li>
     *   <li>{@link OpenTelemetryObservabilityPlugin} — opens an OTel span per operation and,
     *       crucially, injects the W3C {@code traceparent} header into the outbound request via
     *       {@code propagationHeaders()}. The header is then visible in the {@code fanar.wire}
     *       log alongside the other request headers.</li>
     *   <li>{@link MicrometerObservabilityPlugin} — opens a Micrometer Observation per operation
     *       against a bare-bones {@link ObservationRegistry}. No metrics handler is wired so the
     *       observations are dropped, but the plugin path is exercised end-to-end and would emit
     *       Timer / Counter samples in a real Micrometer-wired application.</li>
     * </ul>
     *
     * <p>All three observability plugins are wired through {@link ObservabilityPlugin#compose},
     * so a single live run dispatches every operation event to all of them in parallel — log
     * line + OTel span (with traceparent) + Micrometer Observation.</p>
     */
    public static FanarClient liveWithLogging(FanarJsonCodec codec) {
        return liveBuilder(codec)
                .addInterceptor(WireLoggingInterceptor.builder()
                        .level(WireLoggingInterceptor.Level.BODY)
                        .build())
                .observability(ObservabilityPlugin.compose(
                        new Slf4jObservabilityPlugin(),
                        new OpenTelemetryObservabilityPlugin(otelForDemo()),
                        new MicrometerObservabilityPlugin(ObservationRegistry.create())))
                .build();
    }

    /**
     * Minimal {@link OpenTelemetry} for the live-test demo: a real {@link SdkTracerProvider}
     * (so spans have valid context) plus the W3C trace-context propagator (so
     * {@code traceparent} is injected). No span exporter is wired — spans are produced and
     * dropped. The visible effect is the {@code traceparent} header showing up in the wire log.
     */
    private static OpenTelemetry otelForDemo() {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    private static FanarClient.Builder liveBuilder(FanarJsonCodec codec) {
        String key = apiKey().orElseThrow(() -> new IllegalStateException(
                "FANAR_API_KEY is not set — call this only from a test that declares "
                        + "@EnabledIfEnvironmentVariable(named = \"FANAR_API_KEY\", matches = \".+\")"));
        return FanarClient.builder()
                .apiKey(key)
                .jsonCodec(codec)
                .connectTimeout(Duration.ofSeconds(10))
                .requestTimeout(Duration.ofSeconds(60));
    }
}
