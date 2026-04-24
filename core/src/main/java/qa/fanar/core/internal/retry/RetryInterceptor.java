package qa.fanar.core.internal.retry;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

import qa.fanar.core.FanarException;
import qa.fanar.core.FanarRateLimitException;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.JitterStrategy;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.Interceptor;

/**
 * Retries the HTTP exchange according to the caller's {@link RetryPolicy}.
 *
 * <p>Placed at the head of the interceptor chain so it wraps every later interceptor and the
 * transport. Each retry re-runs the whole chain — rotating bearer tokens, tracing propagation
 * headers, and any user-added cross-cutting logic all re-execute on every attempt.</p>
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>Retries up to {@link RetryPolicy#maxAttempts()} times (so at most {@code maxAttempts - 1}
 *       retries after the initial attempt).</li>
 *   <li>Only retries exceptions accepted by {@link RetryPolicy#retryable()}. The default predicate
 *       retries transient server-side and transport errors; never client-side or content-filter
 *       rejections (ADR-014).</li>
 *   <li>Honours the {@code Retry-After} hint on {@link FanarRateLimitException}: if set, the next
 *       sleep uses that duration instead of the computed back-off curve.</li>
 *   <li>Otherwise sleeps for {@code baseDelay * multiplier^(attempt-1)} capped at {@code maxDelay},
 *       with {@link JitterStrategy} applied (none / full / equal).</li>
 *   <li>Emits one {@code retry_attempt} event on the observation per retry, and sets
 *       {@link FanarObservationAttributes#FANAR_RETRY_COUNT} on exit when any retry happened.</li>
 *   <li>If {@link Thread#interrupt()} cuts the sleep short, restores the interrupt flag and
 *       surfaces a {@link FanarTransportException}.</li>
 * </ul>
 *
 * <p>Internal (ADR-018). Tests construct variants with deterministic {@link Sleeper} and
 * {@link RandomGenerator} via the package-private constructor.</p>
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
                recordRetryCount(chain, attempt - 1);
                return response;
            } catch (FanarException e) {
                if (attempt >= policy.maxAttempts() || !policy.retryable().test(e)) {
                    recordRetryCount(chain, attempt - 1);
                    throw e;
                }
                Duration delay = nextDelay(attempt, e);
                chain.observation().event("retry_attempt");
                sleepOrAbort(delay);
            }
        }
    }

    private Duration nextDelay(int attempt, FanarException e) {
        if (e instanceof FanarRateLimitException rl && rl.retryAfter() != null) {
            return rl.retryAfter();
        }
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

    private static void recordRetryCount(Chain chain, int retries) {
        if (retries > 0) {
            chain.observation().attribute(FanarObservationAttributes.FANAR_RETRY_COUNT, retries);
        }
    }
}
