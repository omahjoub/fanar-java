package qa.fanar.core;

import java.time.Duration;
import java.util.Locale;

/**
 * The server's rate-limit window for the model a call addressed, as reported by the
 * {@code x-ratelimit-limit} / {@code x-ratelimit-remaining} / {@code x-ratelimit-reset} and
 * {@code ratelimit-policy} response headers (ADR-026).
 *
 * <p>Fanar's windows are <em>sliding</em>, per requested model id (observed 2026-08-29, see
 * {@code docs/WIRE_OBSERVATIONS.md}): {@link #remaining()} is the limit minus the requests made in
 * the trailing window, and {@link #reset()} is how long until the <em>oldest</em> of those ages out
 * — the wait for one slot, never a window boundary. A "20 requests/day" policy therefore means 20
 * in any trailing 24 hours.</p>
 *
 * <p>Carried on both HTTP 429 subtypes ({@link FanarRateLimitException#rateLimit()},
 * {@link FanarQuotaExceededException#rateLimit()}) and published as the {@code fanar.ratelimit.*}
 * observation attributes on every response that reports it. The headers are absent on non-model
 * calls, on rejections before admission (401, 403, model gating) and — per the spec — for keys
 * with unlimited quota; the SDK then carries {@code null} instead of an instance.</p>
 *
 * @param limit     requests allowed in the window ({@code x-ratelimit-limit}); non-negative
 * @param remaining requests still available in the window ({@code x-ratelimit-remaining});
 *                  non-negative
 * @param reset     wait until a slot frees ({@code x-ratelimit-reset}); {@code null} when the
 *                  header was absent or unparseable
 * @param policy    the raw {@code ratelimit-policy} value, for example {@code "50;w=60"};
 *                  {@code null} when absent
 * @author Oussama Mahjoub
 */
public record RateLimitInfo(long limit, long remaining, Duration reset, String policy) {

    /**
     * Validates the window.
     *
     * @throws IllegalArgumentException if {@code limit} or {@code remaining} is negative, or
     *                                  {@code reset} is a negative duration
     */
    public RateLimitInfo {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative, got " + limit);
        }
        if (remaining < 0) {
            throw new IllegalArgumentException("remaining must be non-negative, got " + remaining);
        }
        if (reset != null && reset.isNegative()) {
            throw new IllegalArgumentException("reset must be non-negative, got " + reset);
        }
    }

    /**
     * The window length parsed from {@link #policy()}: the {@code w=seconds} parameter of the
     * {@code limit;w=seconds} item whose limit equals {@link #limit()}, or of the first item that
     * carries one when none matches (the header is a list — the spec's own example is
     * {@code 100;w=86400}).
     *
     * @return the window length, or {@code null} when the policy is absent or carries no
     *         parseable window
     */
    public Duration window() {
        if (policy == null) {
            return null;
        }
        Duration fallback = null;
        for (String item : policy.split(",")) {
            String[] parts = item.trim().split(";");
            Long seconds = null;
            for (int i = 1; i < parts.length; i++) {
                String parameter = parts[i].trim().toLowerCase(Locale.ROOT);
                if (parameter.startsWith("w=")) {
                    seconds = parseNonNegative(parameter.substring(2));
                }
            }
            if (seconds == null) {
                continue;
            }
            Duration window = Duration.ofSeconds(seconds);
            Long itemLimit = parseNonNegative(parts[0].trim());
            if (itemLimit != null && itemLimit == limit) {
                return window;
            }
            if (fallback == null) {
                fallback = window;
            }
        }
        return fallback;
    }

    private static Long parseNonNegative(String text) {
        try {
            long value = Long.parseLong(text);
            return value < 0 ? null : value;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
