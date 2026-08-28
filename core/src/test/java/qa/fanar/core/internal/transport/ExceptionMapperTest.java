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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import qa.fanar.core.FanarAuthenticationException;
import qa.fanar.core.FanarAuthorizationException;
import qa.fanar.core.FanarClientClosedRequestException;
import qa.fanar.core.FanarConflictException;
import qa.fanar.core.FanarContentFilterException;
import qa.fanar.core.FanarException;
import qa.fanar.core.FanarGoneException;
import qa.fanar.core.FanarInternalServerException;
import qa.fanar.core.FanarNotFoundException;
import qa.fanar.core.FanarOverloadedException;
import qa.fanar.core.FanarQuotaExceededException;
import qa.fanar.core.FanarRateLimitException;
import qa.fanar.core.FanarTimeoutException;
import qa.fanar.core.FanarTooLargeException;
import qa.fanar.core.FanarUnprocessableException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void status429TreatsPastHttpDateAsAbsent() {
        // RFC 9110 allows an HTTP-date; one already in the past carries no wait to honour.
        FanarException ex = ExceptionMapper.map(response(429, "", Map.of("Retry-After", List.of("Wed, 21 Oct 2015 07:28:00 GMT"))));
        assertNull(((FanarRateLimitException) ex).retryAfter());
    }

    @Test
    void retryAfterHttpDateInTheFutureBecomesTheRemainingWait() {
        Duration wait = ExceptionMapper.parseRetryAfterValue("Fri, 01 Jan 2100 00:00:00 GMT");
        assertNotNull(wait);
        assertTrue(wait.compareTo(Duration.ofDays(365)) > 0);
    }

    @Test
    void retryAfterNonPositiveSecondsAreAbsent() {
        // A zero or negative delay would turn the retry loop into an immediate re-request storm.
        assertNull(ExceptionMapper.parseRetryAfterValue("0"));
        assertNull(ExceptionMapper.parseRetryAfterValue("-5"));
        assertEquals(Duration.ofSeconds(7), ExceptionMapper.parseRetryAfterValue(" 7 "));
    }

    @Test
    void retryAfterGarbageIsAbsent() {
        assertNull(ExceptionMapper.parseRetryAfterValue("soon"));
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
    void status499MapsToClientClosedRequest() {
        assertInstanceOf(FanarClientClosedRequestException.class, ExceptionMapper.map(response(499, "", Map.of())));
    }

    @Test
    void status504MapsToTimeout() {
        assertInstanceOf(FanarTimeoutException.class, ExceptionMapper.map(response(504, "", Map.of())));
    }

    // --- envelope-code routing (the typed code is authoritative; status is the fallback)

    @ParameterizedTest(name = "{0}")
    @MethodSource("envelopeCodes")
    void envelopeCodeDecidesTheSubtype(String wireCode, Class<? extends FanarException> expected) {
        // Status deliberately unknown (418) to prove the typed code wins over status routing.
        String body = "{\"error\":{\"code\":\"" + wireCode + "\",\"message\":\"m\",\"status\":418}}";
        FanarException ex = ExceptionMapper.map(response(418, body, Map.of()));
        assertInstanceOf(expected, ex);
        assertEquals("m", ex.getMessage());
    }

    static Stream<Arguments> envelopeCodes() {
        return Stream.of(
                Arguments.of("content_filter", FanarContentFilterException.class),
                Arguments.of("invalid_authentication", FanarAuthenticationException.class),
                Arguments.of("invalid_authorization", FanarAuthorizationException.class),
                Arguments.of("rate_limit_reached", FanarRateLimitException.class),
                Arguments.of("exceeded_quota", FanarQuotaExceededException.class),
                Arguments.of("internal_server_error", FanarInternalServerException.class),
                Arguments.of("overloaded", FanarOverloadedException.class),
                Arguments.of("timeout", FanarTimeoutException.class),
                Arguments.of("too_large", FanarTooLargeException.class),
                Arguments.of("unprocessable", FanarUnprocessableException.class),
                Arguments.of("conflict", FanarConflictException.class),
                Arguments.of("Not found", FanarNotFoundException.class),
                Arguments.of("no_longer_supported", FanarGoneException.class),
                Arguments.of("client_closed_request", FanarClientClosedRequestException.class));
    }

    @Test
    void quotaEnvelopeOn429IsNotRateLimit() {
        // Both wire as HTTP 429; only the typed code can distinguish permanent quota exhaustion
        // from transient throttling. Pure status routing used to collapse both to rate-limit.
        String body = "{\"error\":{\"code\":\"exceeded_quota\",\"message\":\"quota exhausted\",\"status\":429}}";
        FanarException ex = ExceptionMapper.map(response(429, body, Map.of()));
        assertInstanceOf(FanarQuotaExceededException.class, ex);
        assertEquals("quota exhausted", ex.getMessage());
    }

    @Test
    void quotaEnvelopeOn429CarriesRetryAfter() {
        // The countdown to the next free slot is exactly what a quota-exhausted caller needs.
        String body = "{\"error\":{\"code\":\"exceeded_quota\",\"message\":\"quota exhausted\",\"status\":429}}";
        FanarException ex = ExceptionMapper.map(response(429, body, Map.of("Retry-After", List.of("86400"))));
        assertEquals(Duration.ofHours(24), ((FanarQuotaExceededException) ex).retryAfter());
    }

    @Test
    void nonFilterEnvelopeOn400IsNotContentFilter() {
        String body = "{\"error\":{\"code\":\"unprocessable\",\"message\":\"bad shape\",\"status\":400}}";
        assertInstanceOf(FanarUnprocessableException.class, ExceptionMapper.map(response(400, body, Map.of())));
    }

    @Test
    void rateLimitEnvelopeStillHonorsRetryAfter() {
        String body = "{\"error\":{\"code\":\"rate_limit_reached\",\"message\":\"slow down\",\"status\":429}}";
        FanarException ex = ExceptionMapper.map(response(429, body, Map.of("Retry-After", List.of("7"))));
        assertEquals(Duration.ofSeconds(7), ((FanarRateLimitException) ex).retryAfter());
    }

    @Test
    void unknownEnvelopeCodeFallsBackToStatusRoutingButKeepsTheMessage() {
        String body = "{\"error\":{\"code\":\"flux_capacitor\",\"message\":\"m\",\"status\":503}}";
        FanarException ex = ExceptionMapper.map(response(503, body, Map.of()));
        assertInstanceOf(FanarOverloadedException.class, ex);
        assertEquals("m", ex.getMessage());
    }

    @Test
    void malformedEnvelopeFallsBackToStatusRoutingWithRawBody() {
        FanarException ex = ExceptionMapper.map(response(409, "{\"error\":{\"code\":", Map.of()));
        assertInstanceOf(FanarConflictException.class, ex);
        assertEquals("{\"error\":{\"code\":", ex.getMessage());
    }

    @Test
    void envelopeWithoutMessageFallsBackToRawBody() {
        String body = "{\"error\":{\"code\":\"conflict\",\"status\":409}}";
        FanarException ex = ExceptionMapper.map(response(409, body, Map.of()));
        assertInstanceOf(FanarConflictException.class, ex);
        assertEquals(body, ex.getMessage());
    }

    @Test
    void envelopeWithBlankMessageFallsBackToRawBody() {
        String body = "{\"error\":{\"code\":\"conflict\",\"message\":\"\",\"status\":409}}";
        assertEquals(body, ExceptionMapper.map(response(409, body, Map.of())).getMessage());
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
        assertEquals("Client closed request", ExceptionMapper.map(response(499, "", Map.of())).getMessage());
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
