package qa.fanar.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.function.Supplier;

import qa.fanar.core.audio.AudioClient;
import qa.fanar.core.chat.ChatClient;
import qa.fanar.core.images.ImagesClient;
import qa.fanar.core.internal.audio.AudioClientImpl;
import qa.fanar.core.internal.chat.ChatClientImpl;
import qa.fanar.core.internal.images.ImagesClientImpl;
import qa.fanar.core.internal.moderations.ModerationsClientImpl;
import qa.fanar.core.internal.models.ModelsClientImpl;
import qa.fanar.core.internal.poems.PoemsClientImpl;
import qa.fanar.core.internal.tokens.TokensClientImpl;
import qa.fanar.core.internal.translations.TranslationsClientImpl;
import qa.fanar.core.internal.transport.DefaultHttpTransport;
import qa.fanar.core.internal.transport.HttpTransport;
import qa.fanar.core.moderations.ModerationsClient;
import qa.fanar.core.models.ModelsClient;
import qa.fanar.core.poems.PoemsClient;
import qa.fanar.core.tokens.TokensClient;
import qa.fanar.core.translations.TranslationsClient;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;

/**
 * Main entry point for the Fanar Java SDK.
 *
 * <p>Construct via {@link #builder()}, use try-with-resources for lifecycle, and access domain
 * facades through the accessor methods — today only {@link #chat()}; other domains
 * ({@code audio}, {@code images}, {@code translations}, etc.) land in subsequent PRs as their
 * DTOs arrive.</p>
 *
 * <pre>{@code
 * try (FanarClient client = FanarClient.builder()
 *         .apiKey(System.getenv("FANAR_API_KEY"))
 *         .connectTimeout(Duration.ofSeconds(10))
 *         .build()) {
 *
 *     ChatResponse response = client.chat().send(chatRequest);
 * }
 * }</pre>
 *
 * <p>This is the first-pass contract surface (ADR-016). The domain facades' methods currently
 * throw {@link UnsupportedOperationException}; the transport layer arrives in a follow-up PR.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>If the caller supplied an {@link HttpClient} via {@link Builder#httpClient(HttpClient)},
 * they own its lifecycle; {@link #close()} leaves it untouched. Otherwise, the client owns the
 * internal {@code HttpClient} and {@link #close()} closes it. {@link #close()} is idempotent.</p>
 *
 * <h2>Environment</h2>
 * <p>When not explicitly set, the API key is read from {@code FANAR_API_KEY} and the base URL
 * from {@code FANAR_BASE_URL}. Explicit builder settings always win over the environment.</p>
 */
public final class FanarClient implements AutoCloseable {

    /** Environment variable read for the API key when {@link Builder#apiKey} is not called. */
    public static final String ENV_API_KEY = "FANAR_API_KEY";

    /** Environment variable read for the base URL when {@link Builder#baseUrl} is not called. */
    public static final String ENV_BASE_URL = "FANAR_BASE_URL";

    /** Default Fanar API base URL. */
    public static final URI DEFAULT_BASE_URL = URI.create("https://api.fanar.qa");

    /** Default socket connect timeout. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Default per-request timeout applied by the transport when none is configured. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final Supplier<String> apiKeySupplier;
    private final URI baseUrl;
    private final HttpClient httpClient;
    private final boolean ownsHttpClient;
    private final FanarJsonCodec jsonCodec;
    private final List<Interceptor> interceptors;
    private final RetryPolicy retryPolicy;
    private final ObservabilityPlugin observability;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final String userAgent;
    private final Map<String, String> defaultHeaders;
    private final ChatClient chatClient;
    private final ModelsClient modelsClient;
    private final TokensClient tokensClient;
    private final ModerationsClient moderationsClient;
    private final TranslationsClient translationsClient;
    private final PoemsClient poemsClient;
    private final ImagesClient imagesClient;
    private final AudioClient audioClient;
    private volatile boolean closed = false;

    private FanarClient(Builder b) {
        // Resolve API key: explicit > supplier > env. Fail loud if none.
        Supplier<String> resolvedSupplier = resolveApiKey(b);
        String initial = resolvedSupplier.get();
        if (initial == null || initial.isBlank()) {
            throw new IllegalStateException(
                    "No Fanar API key configured. Call FanarClient.Builder.apiKey(...) or set the "
                            + ENV_API_KEY + " environment variable.");
        }
        this.apiKeySupplier = resolvedSupplier;

        // Resolve base URL: explicit > env > default.
        if (b.baseUrl != null) {
            this.baseUrl = b.baseUrl;
        } else {
            String envUrl = b.envResolver.apply(ENV_BASE_URL);
            this.baseUrl = (envUrl != null && !envUrl.isBlank()) ? URI.create(envUrl) : DEFAULT_BASE_URL;
        }

        this.connectTimeout = b.connectTimeout != null ? b.connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        this.requestTimeout = b.requestTimeout != null ? b.requestTimeout : DEFAULT_REQUEST_TIMEOUT;

        // Resolve HttpClient: user-supplied > default. Ownership flag tracks who closes it.
        if (b.httpClient != null) {
            this.httpClient = b.httpClient;
            this.ownsHttpClient = false;
        } else {
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(this.connectTimeout)
                    .build();
            this.ownsHttpClient = true;
        }

        // Resolve JSON codec: explicit > ServiceLoader discovery. Fail loud if none found.
        this.jsonCodec = resolveJsonCodec(b);

        this.retryPolicy = b.retryPolicy != null ? b.retryPolicy : RetryPolicy.defaults();
        this.observability = b.observability != null ? b.observability : ObservabilityPlugin.noop();
        this.interceptors = List.copyOf(b.interceptors);
        this.userAgent = b.userAgent;
        this.defaultHeaders = Map.copyOf(b.defaultHeaders);

        HttpTransport transport = new DefaultHttpTransport(this.httpClient, this.requestTimeout);
        this.chatClient = new ChatClientImpl(
                this.baseUrl,
                this.jsonCodec,
                this.apiKeySupplier,
                this.interceptors,
                transport,
                this.observability,
                this.retryPolicy,
                this.defaultHeaders,
                this.userAgent);
        this.modelsClient = new ModelsClientImpl(
                this.baseUrl,
                this.jsonCodec,
                this.apiKeySupplier,
                this.interceptors,
                transport,
                this.observability,
                this.retryPolicy,
                this.defaultHeaders,
                this.userAgent);
        this.tokensClient = new TokensClientImpl(
                this.baseUrl,
                this.jsonCodec,
                this.apiKeySupplier,
                this.interceptors,
                transport,
                this.observability,
                this.retryPolicy,
                this.defaultHeaders,
                this.userAgent);
        this.moderationsClient = new ModerationsClientImpl(
                this.baseUrl,
                this.jsonCodec,
                this.apiKeySupplier,
                this.interceptors,
                transport,
                this.observability,
                this.retryPolicy,
                this.defaultHeaders,
                this.userAgent);
        this.translationsClient = new TranslationsClientImpl(
                this.baseUrl,
                this.jsonCodec,
                this.apiKeySupplier,
                this.interceptors,
                transport,
                this.observability,
                this.retryPolicy,
                this.defaultHeaders,
                this.userAgent);
        this.poemsClient = new PoemsClientImpl(
                this.baseUrl,
                this.jsonCodec,
                this.apiKeySupplier,
                this.interceptors,
                transport,
                this.observability,
                this.retryPolicy,
                this.defaultHeaders,
                this.userAgent);
        this.imagesClient = new ImagesClientImpl(
                this.baseUrl,
                this.jsonCodec,
                this.apiKeySupplier,
                this.interceptors,
                transport,
                this.observability,
                this.retryPolicy,
                this.defaultHeaders,
                this.userAgent);
        this.audioClient = new AudioClientImpl(
                this.baseUrl,
                this.jsonCodec,
                this.apiKeySupplier,
                this.interceptors,
                transport,
                this.observability,
                this.retryPolicy,
                this.defaultHeaders,
                this.userAgent);
    }

    private static Supplier<String> resolveApiKey(Builder b) {
        if (b.apiKeySupplier != null) {
            return b.apiKeySupplier;
        }
        Function<String, String> env = b.envResolver;
        return () -> env.apply(ENV_API_KEY);
    }

    private static FanarJsonCodec resolveJsonCodec(Builder b) {
        if (b.jsonCodec != null) {
            return b.jsonCodec;
        }
        var iter = b.codecSources.iterator();
        if (iter.hasNext()) {
            return iter.next();
        }
        throw new IllegalStateException(
                "No FanarJsonCodec found on the classpath. Add fanar-json-jackson3 "
                        + "(for Spring Boot 4 / Jackson 3) or fanar-json-jackson2 "
                        + "(for Spring Boot 3 / Jackson 2) to your build, or pass one explicitly via "
                        + "FanarClient.Builder.jsonCodec(...).");
    }

    /** Start a fresh builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Chat-completion facade. */
    public ChatClient chat() {
        return chatClient;
    }

    /** Models facade — list available models for the configured API key. */
    public ModelsClient models() {
        return modelsClient;
    }

    /** Tokens facade — count tokens for a piece of content under a specific model. */
    public TokensClient tokens() {
        return tokensClient;
    }

    /** Moderations facade — score a prompt/response pair for safety + cultural awareness. */
    public ModerationsClient moderations() {
        return moderationsClient;
    }

    /** Translations facade — translate text between supported language pairs. */
    public TranslationsClient translations() {
        return translationsClient;
    }

    /** Poems facade — generate poems from a natural-language prompt. */
    public PoemsClient poems() {
        return poemsClient;
    }

    /** Images facade — generate images from a natural-language prompt. */
    public ImagesClient images() {
        return imagesClient;
    }

    /** Audio facade — voice CRUD, TTS speech, STT transcription. */
    public AudioClient audio() {
        return audioClient;
    }

    /**
     * Release client-owned resources. When the caller supplied an {@link HttpClient}, its
     * lifecycle is not touched. Idempotent.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (ownsHttpClient) {
            httpClient.close();
        }
    }

    // --- Package-private accessors used by future transport internals and by tests in the
    //     same package. Not part of the public API (ADR-018).

    Supplier<String> apiKeySupplier() { return apiKeySupplier; }
    URI baseUrl() { return baseUrl; }
    HttpClient httpClient() { return httpClient; }
    boolean ownsHttpClient() { return ownsHttpClient; }
    FanarJsonCodec jsonCodec() { return jsonCodec; }
    List<Interceptor> interceptors() { return interceptors; }
    RetryPolicy retryPolicy() { return retryPolicy; }
    ObservabilityPlugin observability() { return observability; }
    Duration connectTimeout() { return connectTimeout; }
    Duration requestTimeout() { return requestTimeout; }
    String userAgent() { return userAgent; }
    Map<String, String> defaultHeaders() { return defaultHeaders; }
    boolean isClosed() { return closed; }

    /**
     * Fluent builder for {@link FanarClient}. Thread-confined — not safe to use from multiple
     * threads concurrently.
     */
    public static final class Builder {

        private Supplier<String> apiKeySupplier;
        private URI baseUrl;
        private HttpClient httpClient;
        private FanarJsonCodec jsonCodec;
        private final List<Interceptor> interceptors = new ArrayList<>();
        private RetryPolicy retryPolicy;
        private ObservabilityPlugin observability;
        private Duration connectTimeout;
        private Duration requestTimeout;
        private String userAgent;
        private final Map<String, String> defaultHeaders = new LinkedHashMap<>();
        Function<String, String> envResolver = System::getenv;
        Iterable<FanarJsonCodec> codecSources = ServiceLoader.load(FanarJsonCodec.class);

        private Builder() {
            // use FanarClient.builder()
        }

        /** Set the API key literal. Overrides any environment fallback. */
        public Builder apiKey(String apiKey) {
            Objects.requireNonNull(apiKey, "apiKey");
            this.apiKeySupplier = () -> apiKey;
            return this;
        }

        /**
         * Set a supplier of API keys. Invoked on every outbound request to support token
         * rotation. Overrides any environment fallback.
         */
        public Builder apiKey(Supplier<String> supplier) {
            this.apiKeySupplier = Objects.requireNonNull(supplier, "supplier");
            return this;
        }

        /** Override the Fanar base URL (default: {@value #DEFAULT_BASE_URL_STRING}). */
        public Builder baseUrl(URI baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            return this;
        }

        /** The unused constant exists only to render the default URL in the Javadoc above. */
        private static final String DEFAULT_BASE_URL_STRING = "https://api.fanar.qa";

        /** Use a caller-supplied HTTP client. Lifecycle remains the caller's responsibility. */
        public Builder httpClient(HttpClient client) {
            this.httpClient = Objects.requireNonNull(client, "client");
            return this;
        }

        /**
         * Use an explicit JSON codec. If not set, the codec is discovered via
         * {@link ServiceLoader}; if discovery finds none, {@link #build()} fails loud.
         */
        public Builder jsonCodec(FanarJsonCodec codec) {
            this.jsonCodec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /** Append an interceptor to the chain. First-added is outermost. */
        public Builder addInterceptor(Interceptor interceptor) {
            Objects.requireNonNull(interceptor, "interceptor");
            this.interceptors.add(interceptor);
            return this;
        }

        /** Override the retry policy (default: {@code RetryPolicy.defaults()}). */
        public Builder retryPolicy(RetryPolicy policy) {
            this.retryPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /** Install an observability plugin (default: {@code ObservabilityPlugin.noop()}). */
        public Builder observability(ObservabilityPlugin plugin) {
            this.observability = Objects.requireNonNull(plugin, "plugin");
            return this;
        }

        /** Override the socket connect timeout. */
        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = Objects.requireNonNull(timeout, "connectTimeout");
            return this;
        }

        /** Override the default per-request timeout. */
        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = Objects.requireNonNull(timeout, "requestTimeout");
            return this;
        }

        /** Override the User-Agent header. */
        public Builder userAgent(String userAgent) {
            this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
            return this;
        }

        /** Append a default header to every outbound request. Repeated calls accumulate. */
        public Builder defaultHeader(String name, String value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            this.defaultHeaders.put(name, value);
            return this;
        }

        /**
         * Package-private hook: inject an environment-variable resolver for tests. Production
         * code never uses this directly — the default resolver is {@code System::getenv}.
         */
        Builder envResolver(Function<String, String> resolver) {
            this.envResolver = Objects.requireNonNull(resolver, "resolver");
            return this;
        }

        /**
         * Package-private hook: inject the set of candidate codecs that normally comes from
         * {@link ServiceLoader#load(Class)}. Production code never uses this directly — the
         * default is the real service-loader discovery.
         */
        Builder codecSources(Iterable<FanarJsonCodec> sources) {
            this.codecSources = Objects.requireNonNull(sources, "sources");
            return this;
        }

        /**
         * Validate configuration and build the client.
         *
         * @throws IllegalStateException if the API key is missing or no JSON codec is available
         */
        public FanarClient build() {
            return new FanarClient(this);
        }
    }
}
