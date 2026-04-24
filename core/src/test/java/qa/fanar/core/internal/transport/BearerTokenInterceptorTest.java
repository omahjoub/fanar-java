package qa.fanar.core.internal.transport;

import org.junit.jupiter.api.Test;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

import javax.net.ssl.SSLSession;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BearerTokenInterceptorTest {

    @Test
    void addsAuthorizationHeader() {
        BearerTokenInterceptor interceptor = new BearerTokenInterceptor(() -> "secret");
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        Interceptor.Chain chain = new Interceptor.Chain() {
            public HttpResponse<InputStream> proceed(HttpRequest request) {
                captured.set(request);
                return stubResponse();
            }
            public ObservationHandle observation() { return ObservabilityPlugin.noop().start("x"); }
        };

        interceptor.intercept(HttpRequest.newBuilder(URI.create("http://t")).GET().build(), chain);

        HttpRequest forwarded = captured.get();
        assertEquals(Optional.of("Bearer secret"), forwarded.headers().firstValue("Authorization"));
    }

    @Test
    void tokenSupplierIsInvokedOnEachCall() {
        AtomicInteger counter = new AtomicInteger();
        BearerTokenInterceptor interceptor = new BearerTokenInterceptor(
                () -> "token-" + counter.incrementAndGet());
        AtomicReference<String> firstToken = new AtomicReference<>();
        AtomicReference<String> secondToken = new AtomicReference<>();
        Interceptor.Chain chain = new Interceptor.Chain() {
            public HttpResponse<InputStream> proceed(HttpRequest request) {
                (firstToken.get() == null ? firstToken : secondToken)
                        .set(request.headers().firstValue("Authorization").orElse(null));
                return stubResponse();
            }
            public ObservationHandle observation() { return ObservabilityPlugin.noop().start("x"); }
        };
        HttpRequest base = HttpRequest.newBuilder(URI.create("http://t")).GET().build();

        interceptor.intercept(base, chain);
        interceptor.intercept(base, chain);

        assertEquals("Bearer token-1", firstToken.get());
        assertEquals("Bearer token-2", secondToken.get());
    }

    @Test
    void preservesExistingHeaders() {
        BearerTokenInterceptor interceptor = new BearerTokenInterceptor(() -> "t");
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        Interceptor.Chain chain = new Interceptor.Chain() {
            public HttpResponse<InputStream> proceed(HttpRequest request) {
                captured.set(request);
                return stubResponse();
            }
            public ObservationHandle observation() { return ObservabilityPlugin.noop().start("x"); }
        };

        HttpRequest original = HttpRequest.newBuilder(URI.create("http://t"))
                .header("X-Custom", "v")
                .GET()
                .build();
        interceptor.intercept(original, chain);

        HttpRequest forwarded = captured.get();
        assertEquals(Optional.of("v"), forwarded.headers().firstValue("X-Custom"));
        assertTrue(forwarded.headers().firstValue("Authorization").isPresent());
    }

    @Test
    void rejectsNullSupplier() {
        assertThrows(NullPointerException.class, () -> new BearerTokenInterceptor(null));
    }

    private static HttpResponse<InputStream> stubResponse() {
        return new HttpResponse<>() {
            public int statusCode() { return 200; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            public InputStream body() { return InputStream.nullInputStream(); }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URI.create("http://t"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
