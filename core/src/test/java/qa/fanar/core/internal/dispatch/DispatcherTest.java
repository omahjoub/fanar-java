package qa.fanar.core.internal.dispatch;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;

import qa.fanar.core.RetryPolicy;
import qa.fanar.core.internal.transport.HttpTransport;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservationHandle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DispatcherTest {

    private static final URI URL = URI.create("https://api.example.com/v1/chat/completions");

    private final RecordingObservation obs = new RecordingObservation();
    private final AtomicReference<HttpRequest> sent = new AtomicReference<>();
    private final HttpResponse<InputStream> canned = response(200);
    private final HttpTransport transport = request -> { sent.set(request); return canned; };

    @Test
    void recordsModelMethodAndUrlThenRunsTheChain() {
        Dispatcher dispatcher = new Dispatcher(transport, RetryPolicy.disabled(), () -> "t", List.of());

        HttpResponse<InputStream> out = dispatcher.dispatch(post(), obs, "Fanar");

        assertSame(canned, out);
        assertEquals("Fanar", obs.attributes.get(FanarObservationAttributes.FANAR_MODEL));
        assertEquals("POST", obs.attributes.get(FanarObservationAttributes.HTTP_METHOD));
        assertEquals(URL.toString(), obs.attributes.get(FanarObservationAttributes.HTTP_URL));
        assertEquals(List.of(FanarObservationAttributes.FANAR_MODEL, FanarObservationAttributes.HTTP_METHOD,
                FanarObservationAttributes.HTTP_URL, FanarObservationAttributes.HTTP_STATUS_CODE,
                FanarObservationAttributes.FANAR_RETRY_COUNT), new ArrayList<>(obs.attributes.keySet()),
                "transport attributes first, then what the retry boundary records");
    }

    @Test
    void omitsTheModelAttributeForCallsWithoutOne() {
        new Dispatcher(transport, RetryPolicy.disabled(), () -> "t", List.of()).dispatch(post(), obs, null);

        assertFalse(obs.attributes.containsKey(FanarObservationAttributes.FANAR_MODEL));
        assertEquals("POST", obs.attributes.get(FanarObservationAttributes.HTTP_METHOD));
    }

    @Test
    void chainRunsRetryThenBearerTokenThenUserInterceptorsThenTransport() {
        List<String> order = new ArrayList<>();
        AtomicReference<String> authSeenByUser = new AtomicReference<>();
        Interceptor first = (request, chain) -> {
            order.add("user-1");
            authSeenByUser.set(request.headers().firstValue("Authorization").orElse(null));
            return chain.proceed(request);
        };
        Interceptor second = (request, chain) -> { order.add("user-2"); return chain.proceed(request); };
        HttpTransport recording = request -> { order.add("transport"); sent.set(request); return canned; };

        new Dispatcher(recording, RetryPolicy.disabled(), () -> "secret", List.of(first, second))
                .dispatch(post(), obs, "Fanar");

        assertEquals(List.of("user-1", "user-2", "transport"), order, "registration order, transport last");
        assertEquals("Bearer secret", authSeenByUser.get(), "the bearer token is applied above the user interceptors");
        assertEquals("Bearer secret", sent.get().headers().firstValue("Authorization").orElse(null));
        assertEquals(0, obs.attributes.get(FanarObservationAttributes.FANAR_RETRY_COUNT), "the retry boundary wraps it all");
    }

    @Test
    void rejectsNullConstructorArgs() {
        RetryPolicy rp = RetryPolicy.disabled();
        assertThrows(NullPointerException.class, () -> new Dispatcher(null, rp, () -> "t", List.of()));
        assertThrows(NullPointerException.class, () -> new Dispatcher(transport, null, () -> "t", List.of()));
        assertThrows(NullPointerException.class, () -> new Dispatcher(transport, rp, null, List.of()));
        assertThrows(NullPointerException.class, () -> new Dispatcher(transport, rp, () -> "t", null));
    }

    // --- helpers

    private static HttpRequest post() {
        return HttpRequest.newBuilder(URL).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
    }

    private static HttpResponse<InputStream> response(int status) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            public InputStream body() { return new ByteArrayInputStream(new byte[0]); }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URL; }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    private static final class RecordingObservation implements ObservationHandle {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        @Override public ObservationHandle attribute(String key, Object value) { attributes.put(key, value); return this; }
        @Override public ObservationHandle event(String name) { return this; }
        @Override public ObservationHandle error(Throwable throwable) { return this; }
        @Override public ObservationHandle child(String operationName) { return this; }
        @Override public Map<String, String> propagationHeaders() { return Map.of(); }
        @Override public void close() { }
    }
}
