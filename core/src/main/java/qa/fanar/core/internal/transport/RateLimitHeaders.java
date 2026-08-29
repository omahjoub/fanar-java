package qa.fanar.core.internal.transport;

import java.net.http.HttpHeaders;
import java.time.Duration;

import qa.fanar.core.RateLimitInfo;

/**
 * Reads Fanar's rate-limit response headers into a {@link RateLimitInfo} (ADR-026).
 *
 * <p>An instance needs {@code x-ratelimit-limit} and {@code x-ratelimit-remaining} to parse as
 * non-negative integers; otherwise the response carries no window and {@code null} is returned.
 * {@code x-ratelimit-reset} and {@code ratelimit-policy} are optional and become {@code null}
 * components when absent or unparseable. Header lookup is case-insensitive.</p>
 *
 * <p>Internal (ADR-018).</p>
 *
 * @author Oussama Mahjoub
 */
public final class RateLimitHeaders {

    static final String LIMIT = "x-ratelimit-limit";
    static final String REMAINING = "x-ratelimit-remaining";
    static final String RESET = "x-ratelimit-reset";
    static final String POLICY = "ratelimit-policy";

    private RateLimitHeaders() {
        // not instantiable
    }

    /**
     * Parse the rate-limit window a response reports.
     *
     * @param headers the response headers
     * @return the window, or {@code null} when the response carries none
     */
    public static RateLimitInfo parse(HttpHeaders headers) {
        Long limit = nonNegativeLong(headers, LIMIT);
        Long remaining = nonNegativeLong(headers, REMAINING);
        if (limit == null || remaining == null) {
            return null;
        }
        Long reset = nonNegativeLong(headers, RESET);
        String policy = headers.firstValue(POLICY).map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
        return new RateLimitInfo(limit, remaining, reset == null ? null : Duration.ofSeconds(reset), policy);
    }

    private static Long nonNegativeLong(HttpHeaders headers, String name) {
        String value = headers.firstValue(name).orElse(null);
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
