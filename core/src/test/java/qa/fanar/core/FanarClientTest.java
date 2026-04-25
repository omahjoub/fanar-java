package qa.fanar.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.ChatClient;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;

import static org.junit.jupiter.api.Assertions.*;

class FanarClientTest {

    // --- Happy-path construction -----------------------------------------------------------

    @Test
    void buildWithExplicitApiKeyAndCodec() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test_123")
                .jsonCodec(dummyCodec())
                .build()) {
            assertNotNull(client);
            assertEquals(FanarClient.DEFAULT_BASE_URL, client.baseUrl());
            assertEquals(FanarClient.DEFAULT_CONNECT_TIMEOUT, client.connectTimeout());
            assertEquals(FanarClient.DEFAULT_REQUEST_TIMEOUT, client.requestTimeout());
        }
    }

    @Test
    void apiKeySupplierIsInvokedOnEachResolution() {
        AtomicInteger invocations = new AtomicInteger();
        try (FanarClient client = FanarClient.builder()
                .apiKey(() -> "rotated-" + invocations.incrementAndGet())
                .jsonCodec(dummyCodec())
                .build()) {
            // The build() path itself invokes the supplier at least once to validate the key;
            // calling it again via the internal accessor re-invokes it (token rotation).
            String first = client.apiKeySupplier().get();
            String second = client.apiKeySupplier().get();
            assertTrue(first.startsWith("rotated-"));
            assertTrue(second.startsWith("rotated-"));
            assertNotEquals(first, second);
        }
    }

    // --- API key resolution ----------------------------------------------------------------

    @Test
    void buildRejectsMissingApiKey() {
        FanarClient.Builder b = FanarClient.builder().jsonCodec(dummyCodec());
        b.envResolver(noEnv());
        assertThrows(IllegalStateException.class, b::build);
    }

    @Test
    void buildRejectsBlankApiKeyFromEnv() {
        FanarClient.Builder b = FanarClient.builder().jsonCodec(dummyCodec());
        b.envResolver(name -> FanarClient.ENV_API_KEY.equals(name) ? "   " : null);
        assertThrows(IllegalStateException.class, b::build);
    }

    @Test
    void envApiKeyIsUsedWhenBuilderApiKeyNotSet() {
        FanarClient.Builder b = FanarClient.builder().jsonCodec(dummyCodec());
        b.envResolver(name -> FanarClient.ENV_API_KEY.equals(name) ? "sk_env_value" : null);
        try (FanarClient client = b.build()) {
            assertEquals("sk_env_value", client.apiKeySupplier().get());
        }
    }

    // --- JSON codec resolution -------------------------------------------------------------

    @Test
    void buildRejectsMissingCodec() {
        FanarClient.Builder b = FanarClient.builder().apiKey("sk_test");
        b.codecSources(java.util.List.of()); // simulate empty ServiceLoader for hermetic test
        IllegalStateException ex = assertThrows(IllegalStateException.class, b::build);
        assertTrue(ex.getMessage().contains("fanar-json-jackson"));
    }

    @Test
    void codecIsResolvedFromServiceLoaderWhenNotExplicit() {
        FanarJsonCodec fromLoader = dummyCodec();
        FanarClient.Builder b = FanarClient.builder().apiKey("sk_test");
        b.codecSources(java.util.List.of(fromLoader));
        try (FanarClient client = b.build()) {
            assertSame(fromLoader, client.jsonCodec());
        }
    }

    @Test
    void buildAcceptsExplicitCodec() {
        FanarJsonCodec codec = dummyCodec();
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(codec)
                .build()) {
            assertSame(codec, client.jsonCodec());
        }
    }

    // --- Base URL resolution ---------------------------------------------------------------

    @Test
    void explicitBaseUrlWinsOverEnv() {
        URI custom = URI.create("https://custom.example.com/v1");
        FanarClient.Builder b = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .baseUrl(custom);
        b.envResolver(name -> FanarClient.ENV_BASE_URL.equals(name) ? "https://env.example.com" : null);
        try (FanarClient client = b.build()) {
            assertEquals(custom, client.baseUrl());
        }
    }

    @Test
    void envBaseUrlIsUsedWhenBuilderBaseUrlNotSet() {
        FanarClient.Builder b = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec());
        b.envResolver(name -> FanarClient.ENV_BASE_URL.equals(name)
                ? "https://proxy.internal:8443" : null);
        try (FanarClient client = b.build()) {
            assertEquals(URI.create("https://proxy.internal:8443"), client.baseUrl());
        }
    }

    // --- HTTP client ownership -------------------------------------------------------------

    @Test
    void defaultHttpClientIsOwned() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build()) {
            assertTrue(client.ownsHttpClient());
            assertNotNull(client.httpClient());
        }
    }

    @Test
    void userSuppliedHttpClientIsNotOwned() {
        HttpClient external = HttpClient.newBuilder().build();
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .httpClient(external)
                .build()) {
            assertFalse(client.ownsHttpClient());
            assertSame(external, client.httpClient());
        }
    }

    // --- Timeouts + optional fields --------------------------------------------------------

    @Test
    void explicitTimeoutsOverrideDefaults() {
        Duration ct = Duration.ofSeconds(3);
        Duration rt = Duration.ofSeconds(90);
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .connectTimeout(ct)
                .requestTimeout(rt)
                .build()) {
            assertEquals(ct, client.connectTimeout());
            assertEquals(rt, client.requestTimeout());
        }
    }

    @Test
    void retryPolicyDefaultsToCanonicalDefaults() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build()) {
            assertEquals(RetryPolicy.defaults().maxAttempts(), client.retryPolicy().maxAttempts());
        }
    }

    @Test
    void explicitRetryPolicyIsUsed() {
        RetryPolicy custom = RetryPolicy.defaults().withMaxAttempts(10);
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .retryPolicy(custom)
                .build()) {
            assertSame(custom, client.retryPolicy());
        }
    }

    @Test
    void blankEnvBaseUrlFallsThroughToDefault() {
        FanarClient.Builder b = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec());
        b.envResolver(name -> FanarClient.ENV_BASE_URL.equals(name) ? "   " : null);
        try (FanarClient client = b.build()) {
            assertEquals(FanarClient.DEFAULT_BASE_URL, client.baseUrl());
        }
    }

    @Test
    void observabilityDefaultsToNoop() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build()) {
            assertSame(ObservabilityPlugin.noop(), client.observability());
        }
    }

    @Test
    void explicitObservabilityOverrides() {
        ObservabilityPlugin p = ObservabilityPlugin.noop(); // still noop, but set explicitly
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .observability(p)
                .build()) {
            assertSame(p, client.observability());
        }
    }

    @Test
    void interceptorsAccumulateInOrder() {
        Interceptor a = (req, chain) -> chain.proceed(req);
        Interceptor b = (req, chain) -> chain.proceed(req);
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .addInterceptor(a)
                .addInterceptor(b)
                .build()) {
            assertEquals(2, client.interceptors().size());
            assertSame(a, client.interceptors().get(0));
            assertSame(b, client.interceptors().get(1));
        }
    }

    @Test
    void interceptorsListIsUnmodifiable() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build()) {
            assertThrows(UnsupportedOperationException.class, () ->
                    client.interceptors().add((req, chain) -> chain.proceed(req)));
        }
    }

    @Test
    void defaultHeadersAccumulate() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .defaultHeader("X-A", "1")
                .defaultHeader("X-B", "2")
                .build()) {
            assertEquals("1", client.defaultHeaders().get("X-A"));
            assertEquals("2", client.defaultHeaders().get("X-B"));
        }
    }

    @Test
    void defaultHeadersMapIsUnmodifiable() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .defaultHeader("X-A", "1")
                .build()) {
            assertThrows(UnsupportedOperationException.class, () ->
                    client.defaultHeaders().put("X-B", "2"));
        }
    }

    @Test
    void userAgentIsPreservedWhenSet() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .userAgent("my-app/1.0")
                .build()) {
            assertEquals("my-app/1.0", client.userAgent());
        }
    }

    @Test
    void userAgentDefaultsToNullWhenNotSet() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build()) {
            assertNull(client.userAgent());
        }
    }

    // --- Builder null-argument rejection ---------------------------------------------------

    @Test
    void builderMethodsRejectNullArguments() {
        FanarClient.Builder b = FanarClient.builder();
        assertThrows(NullPointerException.class, () -> b.apiKey((String) null));
        assertThrows(NullPointerException.class, () -> b.apiKey((java.util.function.Supplier<String>) null));
        assertThrows(NullPointerException.class, () -> b.baseUrl(null));
        assertThrows(NullPointerException.class, () -> b.httpClient(null));
        assertThrows(NullPointerException.class, () -> b.jsonCodec(null));
        assertThrows(NullPointerException.class, () -> b.addInterceptor(null));
        assertThrows(NullPointerException.class, () -> b.retryPolicy(null));
        assertThrows(NullPointerException.class, () -> b.observability(null));
        assertThrows(NullPointerException.class, () -> b.connectTimeout(null));
        assertThrows(NullPointerException.class, () -> b.requestTimeout(null));
        assertThrows(NullPointerException.class, () -> b.userAgent(null));
        assertThrows(NullPointerException.class, () -> b.defaultHeader(null, "v"));
        assertThrows(NullPointerException.class, () -> b.defaultHeader("n", null));
        assertThrows(NullPointerException.class, () -> b.envResolver(null));
    }

    // --- Chat facade (skeleton) ------------------------------------------------------------

    @Test
    void chatReturnsNonNullSameInstance() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build()) {
            ChatClient c1 = client.chat();
            ChatClient c2 = client.chat();
            assertNotNull(c1);
            assertSame(c1, c2);
        }
    }

    @Test
    void chatMethodsRejectNullRequest() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build()) {
            ChatClient chat = client.chat();
            assertThrows(NullPointerException.class, () -> chat.send(null));
            assertThrows(NullPointerException.class, () -> chat.sendAsync(null));
            assertThrows(NullPointerException.class, () -> chat.stream(null));
        }
    }

    // --- Models facade ---------------------------------------------------------------------

    @Test
    void modelsReturnsNonNullSameInstance() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build()) {
            qa.fanar.core.models.ModelsClient m1 = client.models();
            qa.fanar.core.models.ModelsClient m2 = client.models();
            assertNotNull(m1);
            assertSame(m1, m2);
        }
    }

    // --- Tokens facade ---------------------------------------------------------------------

    @Test
    void tokensReturnsNonNullSameInstance() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build()) {
            qa.fanar.core.tokens.TokensClient t1 = client.tokens();
            qa.fanar.core.tokens.TokensClient t2 = client.tokens();
            assertNotNull(t1);
            assertSame(t1, t2);
        }
    }

    // --- Moderations facade ----------------------------------------------------------------

    @Test
    void moderationsReturnsNonNullSameInstance() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build()) {
            qa.fanar.core.moderations.ModerationsClient m1 = client.moderations();
            qa.fanar.core.moderations.ModerationsClient m2 = client.moderations();
            assertNotNull(m1);
            assertSame(m1, m2);
        }
    }

    // --- Translations facade ---------------------------------------------------------------

    @Test
    void poemsReturnsNonNullSameInstance() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build()) {
            qa.fanar.core.poems.PoemsClient p1 = client.poems();
            qa.fanar.core.poems.PoemsClient p2 = client.poems();
            assertNotNull(p1);
            assertSame(p1, p2);
        }
    }

    @Test
    void translationsReturnsNonNullSameInstance() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build()) {
            qa.fanar.core.translations.TranslationsClient t1 = client.translations();
            qa.fanar.core.translations.TranslationsClient t2 = client.translations();
            assertNotNull(t1);
            assertSame(t1, t2);
        }
    }

    // --- Images facade ---------------------------------------------------------------------

    @Test
    void imagesReturnsNonNullSameInstance() {
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build()) {
            qa.fanar.core.images.ImagesClient i1 = client.images();
            qa.fanar.core.images.ImagesClient i2 = client.images();
            assertNotNull(i1);
            assertSame(i1, i2);
        }
    }

    // --- Lifecycle --------------------------------------------------------------------------

    @Test
    void closeMarksClientClosed() {
        FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build();
        assertFalse(client.isClosed());
        client.close();
        assertTrue(client.isClosed());
    }

    @Test
    void closeIsIdempotent() {
        FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build();
        client.close();
        client.close(); // must not throw
        assertTrue(client.isClosed());
    }

    @Test
    void closeDoesNotThrowWhenHttpClientIsUserSupplied() {
        HttpClient external = HttpClient.newBuilder().build();
        FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .httpClient(external)
                .build();
        client.close(); // must not attempt to close the external client
        assertTrue(client.isClosed());
    }

    @Test
    void tryWithResourcesClosesAutomatically() {
        FanarClient reference;
        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .jsonCodec(dummyCodec())
                .build()) {
            reference = client;
            assertFalse(reference.isClosed());
        }
        assertTrue(reference.isClosed());
    }

    // --- Helpers ---------------------------------------------------------------------------

    private static FanarJsonCodec dummyCodec() {
        return new FanarJsonCodec() {
            @Override public <T> T decode(InputStream stream, Class<T> type) throws IOException {
                throw new UnsupportedOperationException("test dummy");
            }
            @Override public void encode(OutputStream stream, Object value) throws IOException {
                throw new UnsupportedOperationException("test dummy");
            }
        };
    }

    private static Function<String, String> noEnv() {
        return name -> null;
    }
}
