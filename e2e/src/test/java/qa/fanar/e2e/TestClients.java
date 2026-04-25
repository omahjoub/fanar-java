package qa.fanar.e2e;

import java.time.Duration;
import java.util.Optional;

import qa.fanar.core.FanarClient;
import qa.fanar.core.spi.FanarJsonCodec;

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
     * Same as {@link #live(FanarJsonCodec)} but with a {@link LoggingInterceptor} attached
     * that prints every request and response to {@code stdout} — useful when a live test is
     * failing and you want to see what's actually going over the wire. Bearer tokens are
     * redacted; request/response bodies are printed verbatim.
     */
    public static FanarClient liveWithLogging(FanarJsonCodec codec) {
        return liveBuilder(codec)
                .addInterceptor(LoggingInterceptor.toStdOut())
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
