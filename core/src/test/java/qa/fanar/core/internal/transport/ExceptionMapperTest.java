package qa.fanar.core.internal.transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarAuthenticationException;
import qa.fanar.core.FanarAuthorizationException;
import qa.fanar.core.FanarConflictException;
import qa.fanar.core.FanarContentFilterException;
import qa.fanar.core.FanarException;
import qa.fanar.core.FanarGoneException;
import qa.fanar.core.FanarInternalServerException;
import qa.fanar.core.FanarNotFoundException;
import qa.fanar.core.FanarOverloadedException;
import qa.fanar.core.FanarRateLimitException;
import qa.fanar.core.FanarTimeoutException;
import qa.fanar.core.FanarTooLargeException;
import qa.fanar.core.FanarUnprocessableException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExceptionMapperTest {

    @Test
    void status400MapsToContentFilter() {
        assertInstanceOf(FanarContentFilterException.class, ExceptionMapper.map(response(400, "blocked", Map.of())));
    }

    @Test
    void status401MapsToAuthentication() {
        assertInstanceOf(FanarAuthenticationException.class, ExceptionMapper.map(response(401, "", Map.of())));
    }

    @Test
    void status403MapsToAuthorization() {
        assertInstanceOf(FanarAuthorizationException.class, ExceptionMapper.map(response(403, "", Map.of())));
    }

    @Test
    void status404MapsToNotFound() {
        assertInstanceOf(FanarNotFoundException.class, ExceptionMapper.map(response(404, "", Map.of())));
    }

    @Test
    void status409MapsToConflict() {
        assertInstanceOf(FanarConflictException.class, ExceptionMapper.map(response(409, "", Map.of())));
    }

    @Test
    void status410MapsToGone() {
        assertInstanceOf(FanarGoneException.class, ExceptionMapper.map(response(410, "", Map.of())));
    }

    @Test
    void status413MapsToTooLarge() {
        assertInstanceOf(FanarTooLargeException.class, ExceptionMapper.map(response(413, "", Map.of())));
    }

    @Test
    void status422MapsToUnprocessable() {
        assertInstanceOf(FanarUnprocessableException.class, ExceptionMapper.map(response(422, "", Map.of())));
    }

    @Test
    void status429MapsToRateLimit() {
        assertInstanceOf(FanarRateLimitException.class, ExceptionMapper.map(response(429, "", Map.of())));
    }

    @Test
    void status429HonorsRetryAfterSeconds() {
        FanarException ex = ExceptionMapper.map(response(429, "", Map.of("Retry-After", List.of("42"))));
        assertEquals(Duration.ofSeconds(42), ((FanarRateLimitException) ex).retryAfter());
    }

    @Test
    void status429HandlesUnparseableRetryAfter() {
        // The HTTP-date form is unsupported for now; the mapper should return null rather than throw.
        FanarException ex = ExceptionMapper.map(response(429, "", Map.of("Retry-After", List.of("Wed, 21 Oct 2015 07:28:00 GMT"))));
        assertNull(((FanarRateLimitException) ex).retryAfter());
    }

    @Test
    void status429WithoutRetryAfterLeavesItNull() {
        FanarException ex = ExceptionMapper.map(response(429, "", Map.of()));
        assertNull(((FanarRateLimitException) ex).retryAfter());
    }

    @Test
    void status500MapsToInternalServer() {
        assertInstanceOf(FanarInternalServerException.class, ExceptionMapper.map(response(500, "", Map.of())));
    }

    @Test
    void status503MapsToOverloaded() {
        assertInstanceOf(FanarOverloadedException.class, ExceptionMapper.map(response(503, "", Map.of())));
    }

    @Test
    void status504MapsToTimeout() {
        assertInstanceOf(FanarTimeoutException.class, ExceptionMapper.map(response(504, "", Map.of())));
    }

    @Test
    void unknownStatusMapsToInternalServer() {
        FanarException ex = ExceptionMapper.map(response(418, "teapot", Map.of()));
        assertInstanceOf(FanarInternalServerException.class, ex);
        assertTrue(ex.getMessage().contains("418"));
    }

    @Test
    void blankBodyFallsBackToCanonicalReason() {
        FanarException ex = ExceptionMapper.map(response(401, "", Map.of()));
        assertEquals("Invalid authentication", ex.getMessage());
    }

    @Test
    void blankBodyCoversEveryKnownStatusReason() {
        // Exercises every branch of defaultReason(): 400 → "Content filtered", etc.
        assertEquals("Content filtered", ExceptionMapper.map(response(400, "", Map.of())).getMessage());
        assertEquals("Invalid authorization", ExceptionMapper.map(response(403, "", Map.of())).getMessage());
        assertEquals("Not found", ExceptionMapper.map(response(404, "", Map.of())).getMessage());
        assertEquals("Conflict", ExceptionMapper.map(response(409, "", Map.of())).getMessage());
        assertEquals("No longer supported", ExceptionMapper.map(response(410, "", Map.of())).getMessage());
        assertEquals("Request entity too large", ExceptionMapper.map(response(413, "", Map.of())).getMessage());
        assertEquals("Unprocessable entity", ExceptionMapper.map(response(422, "", Map.of())).getMessage());
        assertEquals("Rate limit reached", ExceptionMapper.map(response(429, "", Map.of())).getMessage());
        assertEquals("Internal server error", ExceptionMapper.map(response(500, "", Map.of())).getMessage());
        assertEquals("Service overloaded", ExceptionMapper.map(response(503, "", Map.of())).getMessage());
        assertEquals("Upstream timeout", ExceptionMapper.map(response(504, "", Map.of())).getMessage());
    }

    @Test
    void blankBodyForUnknownStatusUsesDefaultReason() {
        // Hits the default branch of defaultReason(): "HTTP <status>".
        FanarException ex = ExceptionMapper.map(response(418, "", Map.of()));
        assertEquals("HTTP 418: HTTP 418", ex.getMessage());
    }

    @Test
    void nonBlankBodyIsUsedAsMessage() {
        FanarException ex = ExceptionMapper.map(response(400, "specific error text", Map.of()));
        assertEquals("specific error text", ex.getMessage());
    }

    @Test
    void readBodyHandlesIOExceptionGracefully() {
        // body() throws on read — map() should still produce an exception
        FanarException ex = ExceptionMapper.map(new HttpResponse<>() {
            public int statusCode() { return 500; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            public InputStream body() {
                return new InputStream() {
                    public int read() throws IOException { throw new IOException("boom"); }
                };
            }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URI.create("http://t"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        });
        assertInstanceOf(FanarInternalServerException.class, ex);
    }

    // --- helpers

    private static HttpResponse<InputStream> response(int status, String body, Map<String, List<String>> headers) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(headers, (a, b) -> true); }
            public InputStream body() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URI.create("http://t"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
