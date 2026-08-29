package qa.fanar.core.internal.retry;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

import qa.fanar.core.FanarException;
import qa.fanar.core.FanarQuotaExceededException;
import qa.fanar.core.FanarRateLimitException;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.JitterStrategy;
import qa.fanar.core.RateLimitInfo;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.internal.transport.ExceptionMapper;
import qa.fanar.core.internal.transport.RateLimitHeaders;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservationHandle;

/**
 * The SDK's error boundary and retry loop, driven by the caller's {@link RetryPolicy}.
 *
 * <p>Placed at the head of the interceptor chain so it wraps every later interceptor and the
 * transport. Each retry re-runs the whole chain — rotating bearer tokens, tracing propagation
 * headers, and any user-added cross-cutting logic all re-execute on every attempt.</p>
 *
 * <p>Error responses (HTTP status ≥ 400) become typed {@link FanarException}s here, once the rest
 * of the chain has returned (ADR-006, ADR-012). User interceptors therefore observe raw error
 * responses — status, headers, body — while the retry decision and the domain facades above it
 * only ever see typed exceptions or successful responses.</p>
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>Retries up to {@link RetryPolicy#maxAttempts()} times (so at most {@code maxAttempts - 1}
 *       retries after the initial attempt).</li>
 *   <li>Only retries exceptions accepted by {@link RetryPolicy#retryable()}. The default predicate
 *       retries transient server-side and transport errors; never client-side or content-filter
 *       rejections (ADR-014).</li>
 *   <li>Honours a server {@code Retry-After} hint up to {@link RetryPolicy#maxDelay()}: the next
 *       sleep uses the hint instead of the computed back-off curve. A hint above {@code maxDelay}
 *       ends retrying immediately — no sleep, no burned attempt — and the exception surfaces with
 *       the hint preserved (ADR-025).</li>
 *   <li>Otherwise sleeps for {@code baseDelay * multiplier^(attempt-1)} capped at {@code maxDelay},
 *       with {@link JitterStrategy} applied (none / full / equal).</li>
 *   <li>Records {@link FanarObservationAttributes#HTTP_STATUS_CODE} for every response received
 *       (the last attempt's status wins) and {@link FanarObservationAttributes#FANAR_RETRY_COUNT}
 *       on every exit, {@code 0} included; emits one {@code retry_attempt} event per retry.</li>
 *   <li>Publishes the server's rate-limit window as the {@code fanar.ratelimit.*} attributes
 *       ({@link FanarObservationAttributes#FANAR_RATELIMIT_LIMIT} and friends) from every response
 *       that carries the headers — successes and 429s alike, the last attempt's values winning;
 *       nothing is recorded for responses without them (ADR-026).</li>
 *   <li>If {@link Thread#interrupt()} cuts the sleep short, restores the interrupt flag and
 *       surfaces a {@link FanarTransportException}.</li>
 * </ul>
 *
 * <p>Internal (ADR-018). Tests construct variants with deterministic {@link Sleeper} and
 * {@link RandomGenerator} via the package-private constructor.</p>
 *
 * @author Oussama Mahjoub
 */
public final class RetryInterceptor implements Interceptor {

    private final RetryPolicy policy;
    private final Sleeper sleeper;
    private final RandomGenerator random;

    public RetryInterceptor(RetryPolicy policy) {
        this(policy, Sleeper.THREAD, RandomGenerator.getDefault());
    }

    RetryInterceptor(RetryPolicy policy, Sleeper sleeper, RandomGenerator random) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public HttpResponse<InputStream> intercept(HttpRequest request, Chain chain) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                HttpResponse<InputStream> response = chain.proceed(request);
                chain.observation().attribute(
                        FanarObservationAttributes.HTTP_STATUS_CODE, response.statusCode());
                recordRateLimit(chain, response);
                if (response.statusCode() >= 400) {
                    throw ExceptionMapper.map(response);
                }
                recordRetryCount(chain, attempt - 1);
                return response;
            } catch (FanarException e) {
                Duration hint = retryAfterOf(e);
                if (attempt >= policy.maxAttempts()
                        || !policy.retryable().test(e)
                        || (hint != null && hint.compareTo(policy.maxDelay()) > 0)) {
                    recordRetryCount(chain, attempt - 1);
                    throw e;
                }
                chain.observation().event("retry_attempt");
                sleepOrAbort(hint != null ? hint : backoff(attempt));
            }
        }
    }

    /**
     * The server's {@code Retry-After} hint, carried by both HTTP 429 subtypes (ADR-006);
     * {@code null} when the server sent none or the exception has no such concept.
     */
    private static Duration retryAfterOf(FanarException e) {
        return switch (e) {
            case FanarRateLimitException rateLimited -> rateLimited.retryAfter();
            case FanarQuotaExceededException quotaExceeded -> quotaExceeded.retryAfter();
            default -> null;
        };
    }

    private Duration backoff(int attempt) {
        long baseMs = policy.baseDelay().toMillis();
        long maxMs = policy.maxDelay().toMillis();
        double expanded = baseMs * Math.pow(policy.backoffMultiplier(), attempt - 1);
        long cappedMs = (long) Math.min(expanded, maxMs);
        return applyJitter(cappedMs);
    }

    private Duration applyJitter(long backoffMs) {
        return switch (policy.jitter()) {
            case NONE -> Duration.ofMillis(backoffMs);
            case FULL -> Duration.ofMillis(backoffMs == 0 ? 0 : random.nextLong(backoffMs + 1));
            case EQUAL -> {
                long half = backoffMs / 2;
                long jitterMs = half == 0 ? 0 : random.nextLong(half + 1);
                yield Duration.ofMillis(half + jitterMs);
            }
        };
    }

    private void sleepOrAbort(Duration delay) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new FanarTransportException("Retry sleep interrupted", ie);
        }
    }

    /** The window the server reported on this attempt, if any — later attempts overwrite. */
    private static void recordRateLimit(Chain chain, HttpResponse<?> response) {
        RateLimitInfo window = RateLimitHeaders.parse(response.headers());
        if (window == null) {
            return;
        }
        ObservationHandle observation = chain.observation();
        observation.attribute(FanarObservationAttributes.FANAR_RATELIMIT_LIMIT, window.limit());
        observation.attribute(FanarObservationAttributes.FANAR_RATELIMIT_REMAINING, window.remaining());
        if (window.reset() != null) {
            observation.attribute(FanarObservationAttributes.FANAR_RATELIMIT_RESET, window.reset().toSeconds());
        }
        if (window.policy() != null) {
            observation.attribute(FanarObservationAttributes.FANAR_RATELIMIT_POLICY, window.policy());
        }
    }

    private static void recordRetryCount(Chain chain, int retries) {
        chain.observation().attribute(FanarObservationAttributes.FANAR_RETRY_COUNT, retries);
    }
}
