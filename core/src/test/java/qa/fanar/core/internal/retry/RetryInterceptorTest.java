package qa.fanar.core.internal.retry;

import org.junit.jupiter.api.Test;
import qa.fanar.core.*;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservationHandle;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class RetryInterceptorTest {

    @Test
    void firstAttemptSuccessReturnsResponseAndDoesNotSleep() {
        HttpResponse<InputStream> expected = stubResponse();
        RecordingChain chain = new RecordingChain(List.of(expected));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryInterceptor interceptor = new RetryInterceptor(
                RetryPolicy.defaults(), sleeper, deterministicRandom());

        HttpResponse<InputStream> actual = interceptor.intercept(baseRequest(), chain);

        assertSame(expected, actual);
        assertEquals(1, chain.calls());
        assertEquals(0, sleeper.sleepCount());
        assertTrue(chain.recorder().retryCountRecorded(), "retry count is recorded on every exit");
        assertEquals(0, chain.recorder().retryCount(), "recorded as 0 — no retry happened");
        assertEquals(List.of(200), chain.recorder().statuses(), "status recorded per attempt");
    }

    @Test
    void transientFailureThenSuccessRetriesAndSleepsBetween() {
        HttpResponse<InputStream> success = stubResponse();
        RecordingChain chain = new RecordingChain(List.of(
                new FanarOverloadedException("busy"),
                success));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryPolicy policy = RetryPolicy.defaults()
                .withJitter(JitterStrategy.NONE)
                .withBaseDelay(Duration.ofMillis(100))
                .withMaxDelay(Duration.ofMillis(100));

        HttpResponse<InputStream> actual = new RetryInterceptor(policy, sleeper, deterministicRandom())
                .intercept(baseRequest(), chain);

        assertSame(success, actual);
        assertEquals(2, chain.calls());
        assertEquals(List.of(Duration.ofMillis(100)), sleeper.sleeps());
        assertEquals(List.of("retry_attempt"), chain.recorder().events());
        assertEquals(1, chain.recorder().retryCount(), "one retry happened");
    }

    @Test
    void exhaustingAttemptsRethrowsLastException() {
        FanarOverloadedException second = new FanarOverloadedException("still busy");
        RecordingChain chain = new RecordingChain(List.of(
                new FanarOverloadedException("busy"),
                second));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryPolicy policy = RetryPolicy.defaults()
                .withMaxAttempts(2)
                .withJitter(JitterStrategy.NONE)
                .withBaseDelay(Duration.ofMillis(1))
                .withMaxDelay(Duration.ofMillis(1));

        FanarOverloadedException thrown = assertThrows(FanarOverloadedException.class, () ->
                new RetryInterceptor(policy, sleeper, deterministicRandom())
                        .intercept(baseRequest(), chain));

        assertSame(second, thrown);
        assertEquals(2, chain.calls());
        assertEquals(1, sleeper.sleepCount());
        assertEquals(1, chain.recorder().retryCount());
    }

    @Test
    void nonRetryableExceptionPropagatesImmediatelyWithoutSleep() {
        FanarAuthenticationException auth = new FanarAuthenticationException("no");
        RecordingChain chain = new RecordingChain(List.of(auth));
        RecordingSleeper sleeper = new RecordingSleeper();

        FanarAuthenticationException thrown = assertThrows(FanarAuthenticationException.class, () ->
                new RetryInterceptor(RetryPolicy.defaults(), sleeper, deterministicRandom())
                        .intercept(baseRequest(), chain));

        assertSame(auth, thrown);
        assertEquals(1, chain.calls());
        assertEquals(0, sleeper.sleepCount());
        assertTrue(chain.recorder().events().isEmpty());
        assertEquals(0, chain.recorder().retryCount());
    }

    @Test
    void retryAfterHeaderOverridesExponentialBackoff() {
        Duration retryAfter = Duration.ofSeconds(7);
        RecordingChain chain = new RecordingChain(List.of(
                new FanarRateLimitException("slow down", retryAfter),
                stubResponse()));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryPolicy policy = RetryPolicy.defaults()
                .withJitter(JitterStrategy.NONE)
                .withBaseDelay(Duration.ofMillis(200))
                .withMaxDelay(Duration.ofSeconds(30));

        new RetryInterceptor(policy, sleeper, deterministicRandom())
                .intercept(baseRequest(), chain);

        // Retry-After wins — exponential curve is ignored for this retry.
        assertEquals(List.of(retryAfter), sleeper.sleeps());
    }

    @Test
    void retryAfterAboveMaxDelayAbortsRetryingImmediately() {
        // ADR-025: a hint the policy cannot honour within maxDelay (e.g. a daily-window reset)
        // must surface the exception at once — no sleep, no burned attempt, hint preserved.
        Duration hint = Duration.ofHours(2);
        FanarRateLimitException rateLimited = new FanarRateLimitException("come back later", hint);
        RecordingChain chain = new RecordingChain(List.of(rateLimited));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryPolicy policy = RetryPolicy.defaults(); // default maxDelay (30 s) is the ceiling

        FanarRateLimitException thrown = assertThrows(FanarRateLimitException.class, () ->
                new RetryInterceptor(policy, sleeper, deterministicRandom())
                        .intercept(baseRequest(), chain));

        assertSame(rateLimited, thrown);
        assertEquals(hint, thrown.retryAfter(), "hint must survive for caller-side scheduling");
        assertEquals(1, chain.calls(), "no retry may be attempted");
        assertEquals(0, sleeper.sleepCount(), "must not sleep at all");
        assertTrue(chain.recorder().events().isEmpty(), "no retry_attempt event");
        assertTrue(chain.recorder().retryCountRecorded(), "the abort is still observable");
        assertEquals(0, chain.recorder().retryCount());
    }

    @Test
    void retryAfterEqualToMaxDelayIsStillHonoured() {
        // Boundary of the ADR-025 ceiling: a hint of exactly maxDelay is within policy. The hint
        // is read from the policy so this test keeps telling ">" from ">=" if the default moves.
        RetryPolicy policy = RetryPolicy.defaults().withJitter(JitterStrategy.NONE);
        Duration hint = policy.maxDelay();
        RecordingChain chain = new RecordingChain(List.of(
                new FanarRateLimitException("slow down", hint),
                stubResponse()));
        RecordingSleeper sleeper = new RecordingSleeper();

        new RetryInterceptor(policy, sleeper, deterministicRandom())
                .intercept(baseRequest(), chain);

        assertEquals(List.of(hint), sleeper.sleeps());
        assertEquals(2, chain.calls());
    }

    @Test
    void rateLimitWithoutRetryAfterFallsBackToExponentialBackoff() {
        RecordingChain chain = new RecordingChain(List.of(
                new FanarRateLimitException("slow down", null),
                stubResponse()));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryPolicy policy = RetryPolicy.defaults()
                .withJitter(JitterStrategy.NONE)
                .withBaseDelay(Duration.ofMillis(200))
                .withMaxDelay(Duration.ofSeconds(30))
                .withBackoffMultiplier(3.0);

        new RetryInterceptor(policy, sleeper, deterministicRandom())
                .intercept(baseRequest(), chain);

        // First retry: base * multiplier^0 = 200ms (jitter NONE).
        assertEquals(List.of(Duration.ofMillis(200)), sleeper.sleeps());
    }

    @Test
    void exponentialBackoffIsCappedAtMaxDelay() {
        RecordingChain chain = new RecordingChain(List.of(
                new FanarInternalServerException("boom"),
                new FanarInternalServerException("boom"),
                new FanarInternalServerException("boom"),
                stubResponse()));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryPolicy policy = RetryPolicy.defaults()
                .withMaxAttempts(4)
                .withJitter(JitterStrategy.NONE)
                .withBaseDelay(Duration.ofMillis(500))
                .withMaxDelay(Duration.ofMillis(1_000))
                .withBackoffMultiplier(10.0);

        new RetryInterceptor(policy, sleeper, deterministicRandom())
                .intercept(baseRequest(), chain);

        // 500, 5000→capped at 1000, 50000→capped at 1000.
        assertEquals(List.of(
                Duration.ofMillis(500),
                Duration.ofMillis(1_000),
                Duration.ofMillis(1_000)
        ), sleeper.sleeps());
    }

    @Test
    void fullJitterProducesDelayInRange() {
        RecordingChain chain = new RecordingChain(List.of(
                new FanarInternalServerException("boom"),
                stubResponse()));
        RecordingSleeper sleeper = new RecordingSleeper();
        // Deterministic: RandomGenerator that always picks the middle.
        RandomGenerator rng = fixedLongGenerator(60);
        RetryPolicy policy = RetryPolicy.defaults()
                .withJitter(JitterStrategy.FULL)
                .withBaseDelay(Duration.ofMillis(100))
                .withMaxDelay(Duration.ofMillis(100));

        new RetryInterceptor(policy, sleeper, rng).intercept(baseRequest(), chain);

        // Expected: random pick in [0, 100] with our stub → 60.
        assertEquals(List.of(Duration.ofMillis(60)), sleeper.sleeps());
    }

    @Test
    void fullJitterOnZeroBackoffYieldsZero() {
        // Corner case: if baseDelay floors to 0ms (impossible via RetryPolicy validation, but the
        // applyJitter helper must still not call random.nextLong(0)). Force by a very low base.
        RecordingChain chain = new RecordingChain(List.of(
                new FanarInternalServerException("boom"),
                stubResponse()));
        RecordingSleeper sleeper = new RecordingSleeper();
        // RandomGenerator that throws if nextLong is called — proves the guard short-circuits.
        RandomGenerator throwingRng = new RandomGenerator() {
            public long nextLong() { throw new AssertionError("must not be invoked"); }
            public long nextLong(long bound) { throw new AssertionError("must not be invoked"); }
        };
        RetryPolicy policy = new RetryPolicy(
                2,
                Duration.ofNanos(1), Duration.ofNanos(1), Duration.ofNanos(1), 1.0,
                JitterStrategy.FULL, RetryPolicy::isDefaultRetryable);

        new RetryInterceptor(policy, sleeper, throwingRng).intercept(baseRequest(), chain);

        assertEquals(List.of(Duration.ZERO), sleeper.sleeps());
    }

    @Test
    void equalJitterYieldsHalfPlusRandomRemainder() {
        RecordingChain chain = new RecordingChain(List.of(
                new FanarInternalServerException("boom"),
                stubResponse()));
        RecordingSleeper sleeper = new RecordingSleeper();
        RandomGenerator rng = fixedLongGenerator(30);
        RetryPolicy policy = RetryPolicy.defaults()
                .withJitter(JitterStrategy.EQUAL)
                .withBaseDelay(Duration.ofMillis(100))
                .withMaxDelay(Duration.ofMillis(100));

        new RetryInterceptor(policy, sleeper, rng).intercept(baseRequest(), chain);

        // half = 50, random pick [0..50] → 30 → total 80.
        assertEquals(List.of(Duration.ofMillis(80)), sleeper.sleeps());
    }

    @Test
    void equalJitterOnZeroBackoffYieldsZero() {
        RecordingChain chain = new RecordingChain(List.of(
                new FanarInternalServerException("boom"),
                stubResponse()));
        RecordingSleeper sleeper = new RecordingSleeper();
        RandomGenerator throwingRng = new RandomGenerator() {
            public long nextLong() { throw new AssertionError("must not be invoked"); }
            public long nextLong(long bound) { throw new AssertionError("must not be invoked"); }
        };
        RetryPolicy policy = new RetryPolicy(
                2,
                Duration.ofNanos(1), Duration.ofNanos(1), Duration.ofNanos(1), 1.0,
                JitterStrategy.EQUAL, RetryPolicy::isDefaultRetryable);

        new RetryInterceptor(policy, sleeper, throwingRng).intercept(baseRequest(), chain);

        assertEquals(List.of(Duration.ZERO), sleeper.sleeps());
    }

    @Test
    void interruptedSleepWrapsAsTransportExceptionAndSetsFlag() {
        RecordingChain chain = new RecordingChain(List.of(
                new FanarInternalServerException("boom"),
                stubResponse()));
        Sleeper interruptingSleeper = d -> { throw new InterruptedException("test"); };
        RetryPolicy policy = RetryPolicy.defaults()
                .withJitter(JitterStrategy.NONE)
                .withBaseDelay(Duration.ofMillis(1))
                .withMaxDelay(Duration.ofMillis(1));

        // Clear any stray interrupt state before the test.
        Thread.interrupted();
        FanarTransportException ex = assertThrows(FanarTransportException.class, () ->
                new RetryInterceptor(policy, interruptingSleeper, deterministicRandom())
                        .intercept(baseRequest(), chain));

        assertInstanceOf(InterruptedException.class, ex.getCause());
        assertTrue(Thread.interrupted(), "interrupt flag must be preserved (and is cleared here)");
    }

    @Test
    void rejectsNullConstructorArgs() {
        assertThrows(NullPointerException.class, () -> new RetryInterceptor(null));
        assertThrows(NullPointerException.class, () ->
                new RetryInterceptor(null, Sleeper.THREAD, deterministicRandom()));
        assertThrows(NullPointerException.class, () ->
                new RetryInterceptor(RetryPolicy.defaults(), null, deterministicRandom()));
        assertThrows(NullPointerException.class, () ->
                new RetryInterceptor(RetryPolicy.defaults(), Sleeper.THREAD, null));
    }

    @Test
    void productionConstructorUsesSharedDefaults() {
        // Single-attempt policy — no sleep path invoked, so neither the default Thread sleeper
        // nor the default random generator matters. This just proves the convenience constructor
        // wires non-null defaults without NPE.
        RecordingChain chain = new RecordingChain(List.of(stubResponse()));
        HttpResponse<InputStream> response = new RetryInterceptor(RetryPolicy.disabled())
                .intercept(baseRequest(), chain);
        assertSame(chain.last(), response);
    }

    @Test
    void errorResponseIsMappedInsideTheChainAndRetried() {
        // The interceptor is the SDK's error boundary: a 5xx HttpResponse coming back through the
        // chain becomes a typed exception here, and the policy decides whether to retry it.
        HttpResponse<InputStream> success = stubResponse();
        RecordingChain chain = new RecordingChain(List.of(
                httpResponse(503, "", Map.of()),
                success));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryPolicy policy = RetryPolicy.defaults()
                .withJitter(JitterStrategy.NONE)
                .withBaseDelay(Duration.ofMillis(100))
                .withMaxDelay(Duration.ofMillis(100));

        HttpResponse<InputStream> actual = new RetryInterceptor(policy, sleeper, deterministicRandom())
                .intercept(baseRequest(), chain);

        assertSame(success, actual);
        assertEquals(2, chain.calls());
        assertEquals(List.of(Duration.ofMillis(100)), sleeper.sleeps());
        assertEquals(List.of(503, 200), chain.recorder().statuses(), "status recorded per attempt");
        assertEquals(1, chain.recorder().retryCount());
    }

    @Test
    void nonRetryableErrorResponseIsMappedAndRethrownWithoutRetry() {
        RecordingChain chain = new RecordingChain(List.of(httpResponse(401, "bad token", Map.of())));
        RecordingSleeper sleeper = new RecordingSleeper();

        FanarAuthenticationException thrown = assertThrows(FanarAuthenticationException.class, () ->
                new RetryInterceptor(RetryPolicy.defaults(), sleeper, deterministicRandom())
                        .intercept(baseRequest(), chain));

        assertEquals("bad token", thrown.getMessage());
        assertEquals(1, chain.calls());
        assertEquals(0, sleeper.sleepCount());
        assertEquals(List.of(401), chain.recorder().statuses());
        assertTrue(chain.recorder().retryCountRecorded());
        assertEquals(0, chain.recorder().retryCount());
    }

    @Test
    void rateLimitResponseHonoursRetryAfterHeader() {
        RecordingChain chain = new RecordingChain(List.of(
                httpResponse(429, "", Map.of("Retry-After", List.of("7"))),
                stubResponse()));
        RecordingSleeper sleeper = new RecordingSleeper();

        new RetryInterceptor(RetryPolicy.defaults(), sleeper, deterministicRandom())
                .intercept(baseRequest(), chain);

        assertEquals(List.of(Duration.ofSeconds(7)), sleeper.sleeps());
        assertEquals(2, chain.calls());
    }

    @Test
    void quotaExceededIsNotRetriedByDefaultButKeepsTheHint() {
        String body = "{\"error\":{\"code\":\"exceeded_quota\",\"message\":\"quota exhausted\",\"status\":429}}";
        RecordingChain chain = new RecordingChain(List.of(
                httpResponse(429, body, Map.of("Retry-After", List.of("86400")))));
        RecordingSleeper sleeper = new RecordingSleeper();

        FanarQuotaExceededException thrown = assertThrows(FanarQuotaExceededException.class, () ->
                new RetryInterceptor(RetryPolicy.defaults(), sleeper, deterministicRandom())
                        .intercept(baseRequest(), chain));

        assertEquals(Duration.ofHours(24), thrown.retryAfter(), "hint survives for caller-side scheduling");
        assertEquals(1, chain.calls());
        assertEquals(0, sleeper.sleepCount());
    }

    @Test
    void predicateOptingIntoQuotaGetsTheSameHintSemantics() {
        // A caller who chooses to retry quota exhaustion gets the ceiling applied to its hint too:
        // honoured up to maxDelay, retrying ended above it.
        RetryPolicy optIn = RetryPolicy.defaults()
                .withJitter(JitterStrategy.NONE)
                .withRetryable(e -> e instanceof FanarQuotaExceededException);

        RecordingChain within = new RecordingChain(List.of(
                new FanarQuotaExceededException("quota", Duration.ofSeconds(5)),
                stubResponse()));
        RecordingSleeper sleeper = new RecordingSleeper();
        new RetryInterceptor(optIn, sleeper, deterministicRandom()).intercept(baseRequest(), within);
        assertEquals(List.of(Duration.ofSeconds(5)), sleeper.sleeps());

        FanarQuotaExceededException above = new FanarQuotaExceededException("quota", Duration.ofDays(1));
        RecordingChain beyond = new RecordingChain(List.of(above));
        RecordingSleeper noSleep = new RecordingSleeper();
        assertSame(above, assertThrows(FanarQuotaExceededException.class, () ->
                new RetryInterceptor(optIn, noSleep, deterministicRandom()).intercept(baseRequest(), beyond)));
        assertEquals(0, noSleep.sleepCount());
    }

    // --- total sleep budget (ADR-027)

    @Test
    void totalDelayBudgetExactlyReachedIsStillHonoured() {
        RecordingChain chain = new RecordingChain(List.of(
                new FanarOverloadedException("busy"),
                new FanarOverloadedException("still busy"),
                stubResponse()));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryPolicy policy = RetryPolicy.builder()
                .jitter(JitterStrategy.NONE)
                .baseDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofMillis(100))
                .maxTotalDelay(Duration.ofMillis(200))
                .build();

        new RetryInterceptor(policy, sleeper, deterministicRandom()).intercept(baseRequest(), chain);

        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(100)), sleeper.sleeps(),
                "two sleeps summing to exactly the budget are both taken");
        assertEquals(3, chain.calls());
        assertEquals(2, chain.recorder().retryCount());
    }

    @Test
    void totalDelayBudgetExceededAbortsBeforeSleeping() {
        FanarOverloadedException second = new FanarOverloadedException("still busy");
        RecordingChain chain = new RecordingChain(List.of(
                new FanarOverloadedException("busy"),
                second,
                stubResponse()));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryPolicy policy = RetryPolicy.builder()
                .jitter(JitterStrategy.NONE)
                .baseDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofMillis(100))
                .maxTotalDelay(Duration.ofMillis(150))
                .build();

        FanarOverloadedException thrown = assertThrows(FanarOverloadedException.class, () ->
                new RetryInterceptor(policy, sleeper, deterministicRandom()).intercept(baseRequest(), chain));

        assertSame(second, thrown, "the exception that would have been retried surfaces");
        assertEquals(List.of(Duration.ofMillis(100)), sleeper.sleeps(),
                "the second sleep would take the total to 200 ms > 150 ms: never started");
        assertEquals(2, chain.calls(), "the attempt after the abort is never made");
        assertEquals(List.of("retry_attempt"), chain.recorder().events(), "one retry happened, the abort is not one");
        assertEquals(1, chain.recorder().retryCount(), "the exit is recorded like every other");
    }

    @Test
    void retryAfterHintBeyondTheRemainingBudgetSurfacesWithTheHintPreserved() {
        // Each hint is within maxDelay (30 s), so ADR-025 alone would sleep twice; the 30 s total
        // budget admits the first 20 s hint and refuses the second (40 s > 30 s).
        Duration hint = Duration.ofSeconds(20);
        FanarRateLimitException second = new FanarRateLimitException("slow down again", hint);
        RecordingChain chain = new RecordingChain(List.of(
                new FanarRateLimitException("slow down", hint),
                second,
                stubResponse()));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryPolicy policy = RetryPolicy.defaults().withMaxTotalDelay(Duration.ofSeconds(30));

        FanarRateLimitException thrown = assertThrows(FanarRateLimitException.class, () ->
                new RetryInterceptor(policy, sleeper, deterministicRandom()).intercept(baseRequest(), chain));

        assertSame(second, thrown);
        assertEquals(hint, thrown.retryAfter(), "hint preserved for caller-side scheduling");
        assertEquals(List.of(hint), sleeper.sleeps(), "only the first hint was slept");
        assertEquals(2, chain.calls());
    }

    // --- rate-limit visibility (ADR-026)

    @Test
    void rateLimitHeadersAreRecordedAsAttributes() {
        RecordingChain chain = new RecordingChain(List.of(httpResponse(200, "", Map.of(
                "x-ratelimit-limit", List.of("50"), "x-ratelimit-remaining", List.of("49"),
                "x-ratelimit-reset", List.of("60"), "ratelimit-policy", List.of("50;w=60")))));

        new RetryInterceptor(RetryPolicy.defaults(), new RecordingSleeper(), deterministicRandom())
                .intercept(baseRequest(), chain);

        Map<String, Object> attributes = chain.recorder().attributes();
        assertEquals(50L, attributes.get(FanarObservationAttributes.FANAR_RATELIMIT_LIMIT));
        assertEquals(49L, attributes.get(FanarObservationAttributes.FANAR_RATELIMIT_REMAINING));
        assertEquals(60L, attributes.get(FanarObservationAttributes.FANAR_RATELIMIT_RESET), "seconds, as on the wire");
        assertEquals("50;w=60", attributes.get(FanarObservationAttributes.FANAR_RATELIMIT_POLICY));
    }

    @Test
    void rateLimitAttributesAreSkippedWhenTheHeadersAreAbsent() {
        RecordingChain chain = new RecordingChain(List.of(stubResponse()));

        new RetryInterceptor(RetryPolicy.defaults(), new RecordingSleeper(), deterministicRandom())
                .intercept(baseRequest(), chain);

        assertFalse(chain.recorder().attributes().containsKey(FanarObservationAttributes.FANAR_RATELIMIT_LIMIT));
        assertFalse(chain.recorder().attributes().containsKey(FanarObservationAttributes.FANAR_RATELIMIT_REMAINING));
    }

    @Test
    void rateLimitAttributesOmitResetAndPolicyWhenThoseHeadersAreAbsent() {
        RecordingChain chain = new RecordingChain(List.of(httpResponse(200, "", Map.of(
                "x-ratelimit-limit", List.of("50"), "x-ratelimit-remaining", List.of("49")))));

        new RetryInterceptor(RetryPolicy.defaults(), new RecordingSleeper(), deterministicRandom())
                .intercept(baseRequest(), chain);

        Map<String, Object> attributes = chain.recorder().attributes();
        assertEquals(50L, attributes.get(FanarObservationAttributes.FANAR_RATELIMIT_LIMIT));
        assertFalse(attributes.containsKey(FanarObservationAttributes.FANAR_RATELIMIT_RESET));
        assertFalse(attributes.containsKey(FanarObservationAttributes.FANAR_RATELIMIT_POLICY));
    }

    @Test
    void rateLimitAttributesOfTheLastAttemptWin() {
        RecordingChain chain = new RecordingChain(List.of(
                httpResponse(503, "", Map.of(
                        "x-ratelimit-limit", List.of("50"), "x-ratelimit-remaining", List.of("5"))),
                httpResponse(200, "", Map.of(
                        "x-ratelimit-limit", List.of("50"), "x-ratelimit-remaining", List.of("49")))));
        RetryPolicy policy = RetryPolicy.defaults()
                .withJitter(JitterStrategy.NONE)
                .withBaseDelay(Duration.ofMillis(1))
                .withMaxDelay(Duration.ofMillis(1));

        new RetryInterceptor(policy, new RecordingSleeper(), deterministicRandom()).intercept(baseRequest(), chain);

        assertEquals(List.of(503, 200), chain.recorder().statuses());
        assertEquals(49L, chain.recorder().attributes().get(FanarObservationAttributes.FANAR_RATELIMIT_REMAINING));
    }

    // --- helpers

    private static HttpRequest baseRequest() {
        return HttpRequest.newBuilder(URI.create("http://t")).GET().build();
    }

    private static HttpResponse<InputStream> stubResponse() {
        return httpResponse(200, "", Map.of());
    }

    private static HttpResponse<InputStream> httpResponse(
            int status, String body, Map<String, List<String>> headers) {
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

    private static RandomGenerator deterministicRandom() {
        return fixedLongGenerator(0);
    }

    private static RandomGenerator fixedLongGenerator(long value) {
        return new RandomGenerator() {
            public long nextLong() { return value; }
            public long nextLong(long bound) { return value; }
        };
    }

    /** In-order queue of scripted outcomes — throws for FanarException, returns for HttpResponse. */
    private static final class RecordingChain implements Interceptor.Chain {
        private final List<Object> outcomes;
        private final AtomicInteger pos = new AtomicInteger();
        private final RecordingObservation observation = new RecordingObservation();

        RecordingChain(List<Object> outcomes) {
            this.outcomes = outcomes;
        }

        int calls() { return pos.get(); }

        RecordingObservation recorder() { return observation; }

        HttpResponse<InputStream> last() {
            for (int i = outcomes.size() - 1; i >= 0; i--) {
                if (outcomes.get(i) instanceof HttpResponse<?> r) {
                    @SuppressWarnings("unchecked")
                    HttpResponse<InputStream> typed = (HttpResponse<InputStream>) r;
                    return typed;
                }
            }
            throw new AssertionError("no HttpResponse in outcomes");
        }

        @Override
        public HttpResponse<InputStream> proceed(HttpRequest request) {
            Object outcome = outcomes.get(pos.getAndIncrement());
            if (outcome instanceof RuntimeException e) throw e;
            @SuppressWarnings("unchecked")
            HttpResponse<InputStream> typed = (HttpResponse<InputStream>) outcome;
            return typed;
        }

        @Override
        public ObservationHandle observation() { return observation; }
    }

    private static final class RecordingObservation implements ObservationHandle {
        private final List<String> events = new ArrayList<>();
        private final List<Integer> statuses = new ArrayList<>();
        private final AtomicReference<Object> retryCount = new AtomicReference<>();
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        List<String> events() { return events; }
        Map<String, Object> attributes() { return attributes; }
        List<Integer> statuses() { return statuses; }
        boolean retryCountRecorded() { return retryCount.get() != null; }

        int retryCount() {
            Object v = retryCount.get();
            return v == null ? 0 : (int) v;
        }

        @Override
        public ObservationHandle attribute(String key, Object value) {
            attributes.put(key, value);
            if (FanarObservationAttributes.FANAR_RETRY_COUNT.equals(key)) {
                retryCount.set(value);
            } else if (FanarObservationAttributes.HTTP_STATUS_CODE.equals(key)) {
                statuses.add((Integer) value);
            }
            return this;
        }
        @Override
        public ObservationHandle event(String name) { events.add(name); return this; }
        @Override
        public ObservationHandle error(Throwable throwable) { return this; }
        @Override
        public ObservationHandle child(String operationName) { return this; }
        @Override
        public Map<String, String> propagationHeaders() { return Map.of(); }
        @Override
        public void close() { }
    }

    private static final class RecordingSleeper implements Sleeper {
        private final List<Duration> sleeps = new ArrayList<>();

        List<Duration> sleeps() { return sleeps; }
        int sleepCount() { return sleeps.size(); }

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration);
        }
    }

}
