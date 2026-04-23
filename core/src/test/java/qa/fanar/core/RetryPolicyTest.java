package qa.fanar.core;

import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {

    // --- presets

    @Test
    void defaultsMatchAdr014() {
        RetryPolicy p = RetryPolicy.defaults();
        assertEquals(3, p.maxAttempts());
        assertEquals(Duration.ofMillis(500), p.baseDelay());
        assertEquals(Duration.ofSeconds(30), p.maxDelay());
        assertEquals(2.0, p.backoffMultiplier());
        assertEquals(JitterStrategy.FULL, p.jitter());
    }

    @Test
    void defaultsRetryableMatchesDefaultPredicate() {
        RetryPolicy p = RetryPolicy.defaults();
        assertTrue(p.retryable().test(new FanarRateLimitException("x")));
        assertFalse(p.retryable().test(new FanarAuthenticationException("x")));
    }

    @Test
    void disabledHasSingleAttempt() {
        assertEquals(1, RetryPolicy.disabled().maxAttempts());
    }

    @Test
    void disabledIsStillValid() {
        RetryPolicy p = RetryPolicy.disabled();
        assertTrue(p.baseDelay().isPositive());
        assertTrue(p.maxDelay().isPositive());
    }

    // --- constructor validation

    @Test
    void rejectsMaxAttemptsBelowOne() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryPolicy(0, Duration.ofMillis(500), Duration.ofSeconds(30), 2.0,
                        JitterStrategy.FULL, e -> true));
    }

    @Test
    void rejectsNullBaseDelay() {
        assertThrows(NullPointerException.class, () ->
                new RetryPolicy(3, null, Duration.ofSeconds(30), 2.0,
                        JitterStrategy.FULL, e -> true));
    }

    @Test
    void rejectsNegativeBaseDelay() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryPolicy(3, Duration.ofMillis(-500), Duration.ofSeconds(30), 2.0,
                        JitterStrategy.FULL, e -> true));
    }

    @Test
    void rejectsZeroBaseDelay() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryPolicy(3, Duration.ZERO, Duration.ofSeconds(30), 2.0,
                        JitterStrategy.FULL, e -> true));
    }

    @Test
    void rejectsNullMaxDelay() {
        assertThrows(NullPointerException.class, () ->
                new RetryPolicy(3, Duration.ofMillis(500), null, 2.0,
                        JitterStrategy.FULL, e -> true));
    }

    @Test
    void rejectsZeroMaxDelay() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryPolicy(3, Duration.ofMillis(500), Duration.ZERO, 2.0,
                        JitterStrategy.FULL, e -> true));
    }

    @Test
    void rejectsNegativeMaxDelay() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryPolicy(3, Duration.ofMillis(500), Duration.ofMillis(-30), 2.0,
                        JitterStrategy.FULL, e -> true));
    }

    @Test
    void rejectsMaxDelayLessThanBaseDelay() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofMillis(500), 2.0,
                        JitterStrategy.FULL, e -> true));
    }

    @Test
    void rejectsBackoffMultiplierBelowOne() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetryPolicy(3, Duration.ofMillis(500), Duration.ofSeconds(30), 0.5,
                        JitterStrategy.FULL, e -> true));
    }

    @Test
    void rejectsNullJitter() {
        assertThrows(NullPointerException.class, () ->
                new RetryPolicy(3, Duration.ofMillis(500), Duration.ofSeconds(30), 2.0,
                        null, e -> true));
    }

    @Test
    void rejectsNullRetryable() {
        assertThrows(NullPointerException.class, () ->
                new RetryPolicy(3, Duration.ofMillis(500), Duration.ofSeconds(30), 2.0,
                        JitterStrategy.FULL, null));
    }

    // --- with* methods

    @Test
    void withMaxAttemptsReplacesOnlyThatField() {
        RetryPolicy p = RetryPolicy.defaults();
        RetryPolicy q = p.withMaxAttempts(5);
        assertEquals(5, q.maxAttempts());
        assertEquals(p.baseDelay(), q.baseDelay());
        assertEquals(p.maxDelay(), q.maxDelay());
        assertEquals(p.backoffMultiplier(), q.backoffMultiplier());
        assertEquals(p.jitter(), q.jitter());
        assertSame(p.retryable(), q.retryable());
        assertNotSame(p, q);
    }

    @Test
    void withBaseDelayReplacesOnlyThatField() {
        RetryPolicy p = RetryPolicy.defaults();
        RetryPolicy q = p.withBaseDelay(Duration.ofMillis(100));
        assertEquals(Duration.ofMillis(100), q.baseDelay());
        assertEquals(p.maxAttempts(), q.maxAttempts());
    }

    @Test
    void withMaxDelayReplacesOnlyThatField() {
        RetryPolicy p = RetryPolicy.defaults();
        RetryPolicy q = p.withMaxDelay(Duration.ofMinutes(1));
        assertEquals(Duration.ofMinutes(1), q.maxDelay());
        assertEquals(p.maxAttempts(), q.maxAttempts());
    }

    @Test
    void withBackoffMultiplierReplacesOnlyThatField() {
        RetryPolicy p = RetryPolicy.defaults();
        RetryPolicy q = p.withBackoffMultiplier(3.0);
        assertEquals(3.0, q.backoffMultiplier());
        assertEquals(p.maxAttempts(), q.maxAttempts());
    }

    @Test
    void withJitterReplacesOnlyThatField() {
        RetryPolicy p = RetryPolicy.defaults();
        RetryPolicy q = p.withJitter(JitterStrategy.NONE);
        assertEquals(JitterStrategy.NONE, q.jitter());
        assertEquals(p.maxAttempts(), q.maxAttempts());
    }

    @Test
    void withRetryableReplacesOnlyThatField() {
        RetryPolicy p = RetryPolicy.defaults();
        RetryPolicy q = p.withRetryable(e -> false);
        assertFalse(q.retryable().test(new FanarRateLimitException("x")));
        assertEquals(p.maxAttempts(), q.maxAttempts());
    }

    @Test
    void withMethodsRevalidate() {
        assertThrows(IllegalArgumentException.class, () ->
                RetryPolicy.defaults().withMaxAttempts(0));
        assertThrows(IllegalArgumentException.class, () ->
                RetryPolicy.defaults().withBaseDelay(Duration.ZERO));
        assertThrows(NullPointerException.class, () ->
                RetryPolicy.defaults().withJitter(null));
    }

    // --- isDefaultRetryable matrix (ADR-014)

    @ParameterizedTest(name = "{0}")
    @MethodSource("retryableMatrix")
    void isDefaultRetryableMatchesMatrix(String name, FanarException e, boolean expected) {
        assertEquals(expected, RetryPolicy.isDefaultRetryable(e));
    }

    static Stream<Arguments> retryableMatrix() {
        return Stream.of(
                // Server-side transient — retryable
                Arguments.of("FanarRateLimitException",       new FanarRateLimitException("x"),       true),
                Arguments.of("FanarOverloadedException",      new FanarOverloadedException("x"),      true),
                Arguments.of("FanarTimeoutException",         new FanarTimeoutException("x"),         true),
                Arguments.of("FanarInternalServerException",  new FanarInternalServerException("x"),  true),
                // Transport — retryable
                Arguments.of("FanarTransportException",       new FanarTransportException("x"),       true),
                // Client-side permanent — not retryable
                Arguments.of("FanarAuthenticationException",  new FanarAuthenticationException("x"),  false),
                Arguments.of("FanarAuthorizationException",   new FanarAuthorizationException("x"),   false),
                Arguments.of("FanarQuotaExceededException",   new FanarQuotaExceededException("x"),   false),
                Arguments.of("FanarNotFoundException",        new FanarNotFoundException("x"),        false),
                Arguments.of("FanarConflictException",        new FanarConflictException("x"),        false),
                Arguments.of("FanarGoneException",            new FanarGoneException("x"),            false),
                Arguments.of("FanarTooLargeException",        new FanarTooLargeException("x"),        false),
                Arguments.of("FanarUnprocessableException",   new FanarUnprocessableException("x"),   false),
                // Content filter — not retryable
                Arguments.of("FanarContentFilterException",   new FanarContentFilterException("x"),   false)
        );
    }

    @Test
    void isDefaultRetryableRejectsNull() {
        assertThrows(NullPointerException.class, () -> RetryPolicy.isDefaultRetryable(null));
    }
}
