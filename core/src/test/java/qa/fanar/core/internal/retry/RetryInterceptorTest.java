package qa.fanar.core.internal.retry;

import org.junit.jupiter.api.Test;
import qa.fanar.core.*;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservationHandle;

import javax.net.ssl.SSLSession;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
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
        assertEquals(0, chain.recorder().retryCount(), "no retries → attribute must not be set");
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
                Duration.ofNanos(1), Duration.ofNanos(1), 1.0,
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
                Duration.ofNanos(1), Duration.ofNanos(1), 1.0,
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

    // --- helpers

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
        private final AtomicReference<Object> retryCount = new AtomicReference<>();

        List<String> events() { return events; }

        int retryCount() {
            Object v = retryCount.get();
            return v == null ? 0 : (int) v;
        }

        @Override
        public ObservationHandle attribute(String key, Object value) {
            if (FanarObservationAttributes.FANAR_RETRY_COUNT.equals(key)) {
                retryCount.set(value);
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
