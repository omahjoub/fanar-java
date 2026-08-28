package qa.fanar.e2e;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import javax.net.ssl.SSLSession;

import qa.fanar.core.spi.Interceptor;

/**
 * {@link Interceptor} that snapshots the most recent HTTP response — headers plus drained
 * body — so a test can assert transport-level metadata the SDK's typed DTOs don't carry, or
 * decode the same wire bytes through more than one codec.
 *
 * <p>Used by {@code LiveChatCompletionsTest} §7 to pin the rate-limit response headers
 * ({@code x-ratelimit-*}, {@code ratelimit-policy}) documented in the 2026-08-27 spec
 * refresh. The byte capture also supports codec-parity checks: send one request via the SDK,
 * capture the raw bytes, then feed them to both Jackson adapters and assert the resulting
 * records are {@code .equals()}.</p>
 *
 * <p>Bytes are buffered in memory and replayed via a fresh {@code ByteArrayInputStream} so the
 * downstream subscriber still sees a usable response. {@link #lastResponseBody()} returns a
 * defensive copy; {@link #lastResponseHeaders()} returns the immutable JDK view.</p>
 */
public final class CapturingInterceptor implements Interceptor {

    private volatile byte[] lastResponseBody;
    private volatile HttpHeaders lastResponseHeaders;

    /** Bytes from the most recent response, or {@code null} if no exchange has occurred yet. */
    public byte[] lastResponseBody() {
        byte[] snapshot = lastResponseBody;
        return snapshot == null ? null : snapshot.clone();
    }

    /** Headers from the most recent response, or {@code null} if no exchange has occurred yet. */
    public HttpHeaders lastResponseHeaders() {
        return lastResponseHeaders;
    }

    @Override
    public HttpResponse<InputStream> intercept(HttpRequest request, Chain chain) {
        HttpResponse<InputStream> response = chain.proceed(request);
        byte[] body = drain(response.body());
        this.lastResponseBody = body;
        this.lastResponseHeaders = response.headers();
        return replay(response, body);
    }

    private static byte[] drain(InputStream body) {
        try (InputStream in = body) {
            return in.readAllBytes();
        } catch (IOException e) {
            return ("(body read failed: " + e.getMessage() + ")").getBytes(StandardCharsets.UTF_8);
        }
    }

    private static HttpResponse<InputStream> replay(HttpResponse<InputStream> original, byte[] body) {
        return new HttpResponse<>() {
            public int statusCode() { return original.statusCode(); }
            public HttpRequest request() { return original.request(); }
            public Optional<HttpResponse<InputStream>> previousResponse() { return original.previousResponse(); }
            public HttpHeaders headers() { return original.headers(); }
            public InputStream body() { return new ByteArrayInputStream(body); }
            public Optional<SSLSession> sslSession() { return original.sslSession(); }
            public URI uri() { return original.uri(); }
            public HttpClient.Version version() { return original.version(); }
        };
    }
}
