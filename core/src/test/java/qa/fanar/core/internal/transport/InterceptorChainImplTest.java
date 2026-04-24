package qa.fanar.core.internal.transport;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;

import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InterceptorChainImplTest {

    @Test
    void emptyChainHitsTransportDirectly() {
        HttpResponse<InputStream> expected = stubResponse();
        HttpTransport transport = req -> expected;

        ObservationHandle obs = ObservabilityPlugin.noop().start("x");
        InterceptorChainImpl chain = new InterceptorChainImpl(List.of(), transport, obs);

        HttpResponse<InputStream> result = chain.proceed(baseRequest());
        assertSame(expected, result);
        assertSame(obs, chain.observation());
    }

    @Test
    void interceptorsRunInRegistrationOrder() {
        List<String> trace = new ArrayList<>();
        Interceptor a = (req, ch) -> { trace.add("a"); return ch.proceed(req); };
        Interceptor b = (req, ch) -> { trace.add("b"); return ch.proceed(req); };
        Interceptor c = (req, ch) -> { trace.add("c"); return ch.proceed(req); };
        HttpTransport transport = req -> { trace.add("transport"); return stubResponse(); };

        ObservationHandle obs = ObservabilityPlugin.noop().start("x");
        new InterceptorChainImpl(List.of(a, b, c), transport, obs).proceed(baseRequest());

        assertEquals(List.of("a", "b", "c", "transport"), trace);
    }

    @Test
    void interceptorCanShortCircuitBeforeTransport() {
        HttpResponse<InputStream> cached = stubResponse();
        Interceptor shortCircuit = (req, ch) -> cached;
        HttpTransport transport = req -> { throw new AssertionError("should not reach transport"); };

        ObservationHandle obs = ObservabilityPlugin.noop().start("x");
        HttpResponse<InputStream> result = new InterceptorChainImpl(List.of(shortCircuit), transport, obs)
                .proceed(baseRequest());

        assertSame(cached, result);
    }

    @Test
    void observationPropagatesToNestedChainSteps() {
        ObservationHandle obs = ObservabilityPlugin.noop().start("x");
        Interceptor inner = (req, ch) -> {
            assertSame(obs, ch.observation());
            return ch.proceed(req);
        };
        HttpTransport transport = req -> stubResponse();

        new InterceptorChainImpl(List.of(inner), transport, obs).proceed(baseRequest());
    }

    @Test
    void rejectsNullRequest() {
        HttpTransport transport = req -> stubResponse();
        InterceptorChainImpl chain = new InterceptorChainImpl(
                List.of(), transport, ObservabilityPlugin.noop().start("x"));
        assertThrows(NullPointerException.class, () -> chain.proceed(null));
    }

    @Test
    void rejectsNullConstructorArgs() {
        HttpTransport transport = req -> stubResponse();
        ObservationHandle obs = ObservabilityPlugin.noop().start("x");
        assertThrows(NullPointerException.class, () -> new InterceptorChainImpl(null, transport, obs));
        assertThrows(NullPointerException.class, () -> new InterceptorChainImpl(List.of(), null, obs));
        assertThrows(NullPointerException.class, () -> new InterceptorChainImpl(List.of(), transport, null));
    }

    private static HttpRequest baseRequest() {
        return HttpRequest.newBuilder(URI.create("http://t")).GET().build();
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
