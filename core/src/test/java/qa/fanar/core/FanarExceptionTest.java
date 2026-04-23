package qa.fanar.core;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FanarExceptionTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("apiSubtypes")
    void apiSubtypeHasExpectedCodeAndStatus(String name, FanarException e, ErrorCode code, int status) {
        assertEquals(code, e.code());
        assertEquals(status, e.httpStatus());
        assertEquals("msg", e.getMessage());
    }

    static Stream<Arguments> apiSubtypes() {
        return Stream.of(
                Arguments.of("FanarAuthenticationException",
                        new FanarAuthenticationException("msg"), ErrorCode.INVALID_AUTHENTICATION, 401),
                Arguments.of("FanarAuthorizationException",
                        new FanarAuthorizationException("msg"), ErrorCode.INVALID_AUTHORIZATION, 403),
                Arguments.of("FanarNotFoundException",
                        new FanarNotFoundException("msg"), ErrorCode.NOT_FOUND, 404),
                Arguments.of("FanarConflictException",
                        new FanarConflictException("msg"), ErrorCode.CONFLICT, 409),
                Arguments.of("FanarGoneException",
                        new FanarGoneException("msg"), ErrorCode.NO_LONGER_SUPPORTED, 410),
                Arguments.of("FanarTooLargeException",
                        new FanarTooLargeException("msg"), ErrorCode.TOO_LARGE, 413),
                Arguments.of("FanarUnprocessableException",
                        new FanarUnprocessableException("msg"), ErrorCode.UNPROCESSABLE, 422),
                Arguments.of("FanarQuotaExceededException",
                        new FanarQuotaExceededException("msg"), ErrorCode.EXCEEDED_QUOTA, 429),
                Arguments.of("FanarInternalServerException",
                        new FanarInternalServerException("msg"), ErrorCode.INTERNAL_SERVER_ERROR, 500),
                Arguments.of("FanarOverloadedException",
                        new FanarOverloadedException("msg"), ErrorCode.OVERLOADED, 503),
                Arguments.of("FanarTimeoutException",
                        new FanarTimeoutException("msg"), ErrorCode.TIMEOUT, 504),
                Arguments.of("FanarRateLimitException",
                        new FanarRateLimitException("msg"), ErrorCode.RATE_LIMIT_REACHED, 429),
                Arguments.of("FanarContentFilterException",
                        new FanarContentFilterException("msg"), ErrorCode.CONTENT_FILTER, 400)
        );
    }

    @Test
    void transportExceptionHasNoCodeOrStatus() {
        var e = new FanarTransportException("network lost");
        assertNull(e.code());
        assertEquals(-1, e.httpStatus());
    }

    @Test
    void transportExceptionPreservesCause() {
        var cause = new IOException("boom");
        var e = new FanarTransportException("network lost", cause);
        assertSame(cause, e.getCause());
    }

    @Test
    void rateLimitCarriesRetryAfter() {
        var e = new FanarRateLimitException("slow down", Duration.ofSeconds(5));
        assertEquals(Duration.ofSeconds(5), e.retryAfter());
    }

    @Test
    void rateLimitDefaultsRetryAfterToNull() {
        var e = new FanarRateLimitException("slow down");
        assertNull(e.retryAfter());
    }

    @Test
    void contentFilterCarriesFilterType() {
        var e = new FanarContentFilterException("blocked", ContentFilterType.SAFETY);
        assertEquals(ContentFilterType.SAFETY, e.filterType());
    }

    @Test
    void contentFilterDefaultsFilterTypeToNull() {
        var e = new FanarContentFilterException("blocked");
        assertNull(e.filterType());
    }

    /**
     * Compile-time evidence that the sealed hierarchy is exhaustive: this switch has no
     * default case and must compile. Any future addition of a permitted subtype that isn't
     * handled below would break the build, which is the intended safety net (see ADR-005,
     * ADR-010, ADR-018).
     */
    @Test
    void sealedHierarchyIsExhaustive() {
        FanarException e = new FanarRateLimitException("limited");
        String category = switch (e) {
            case FanarTransportException t     -> "transport";
            case FanarContentFilterException c -> "content-filter";
            case FanarClientException c        -> "client";
            case FanarServerException s        -> "server";
        };
        assertEquals("server", category);
    }

    @Test
    void rejectsNullMessage() {
        assertThrows(NullPointerException.class, () -> new FanarNotFoundException(null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subtypesWithCause")
    void causeConstructorPreservesCause(String name, Function<Throwable, FanarException> factory) {
        var cause = new RuntimeException("inner");
        var e = factory.apply(cause);
        assertSame(cause, e.getCause());
        assertEquals("msg-with-cause", e.getMessage());
    }

    static Stream<Arguments> subtypesWithCause() {
        return Stream.of(
                Arguments.of("FanarAuthenticationException",
                        (Function<Throwable, FanarException>) c -> new FanarAuthenticationException("msg-with-cause", c)),
                Arguments.of("FanarAuthorizationException",
                        (Function<Throwable, FanarException>) c -> new FanarAuthorizationException("msg-with-cause", c)),
                Arguments.of("FanarNotFoundException",
                        (Function<Throwable, FanarException>) c -> new FanarNotFoundException("msg-with-cause", c)),
                Arguments.of("FanarConflictException",
                        (Function<Throwable, FanarException>) c -> new FanarConflictException("msg-with-cause", c)),
                Arguments.of("FanarGoneException",
                        (Function<Throwable, FanarException>) c -> new FanarGoneException("msg-with-cause", c)),
                Arguments.of("FanarTooLargeException",
                        (Function<Throwable, FanarException>) c -> new FanarTooLargeException("msg-with-cause", c)),
                Arguments.of("FanarUnprocessableException",
                        (Function<Throwable, FanarException>) c -> new FanarUnprocessableException("msg-with-cause", c)),
                Arguments.of("FanarQuotaExceededException",
                        (Function<Throwable, FanarException>) c -> new FanarQuotaExceededException("msg-with-cause", c)),
                Arguments.of("FanarInternalServerException",
                        (Function<Throwable, FanarException>) c -> new FanarInternalServerException("msg-with-cause", c)),
                Arguments.of("FanarOverloadedException",
                        (Function<Throwable, FanarException>) c -> new FanarOverloadedException("msg-with-cause", c)),
                Arguments.of("FanarTimeoutException",
                        (Function<Throwable, FanarException>) c -> new FanarTimeoutException("msg-with-cause", c)),
                Arguments.of("FanarRateLimitException",
                        (Function<Throwable, FanarException>) c -> new FanarRateLimitException("msg-with-cause", Duration.ofSeconds(1), c)),
                Arguments.of("FanarContentFilterException",
                        (Function<Throwable, FanarException>) c -> new FanarContentFilterException("msg-with-cause", ContentFilterType.SAFETY, c))
        );
    }
}
