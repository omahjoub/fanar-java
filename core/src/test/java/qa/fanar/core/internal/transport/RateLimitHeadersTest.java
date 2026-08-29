package qa.fanar.core.internal.transport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import qa.fanar.core.RateLimitInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitHeadersTest {

    @Test
    void parsesTheFourHeaders() {
        RateLimitInfo info = RateLimitHeaders.parse(headers(Map.of(
                "x-ratelimit-limit", List.of("50"),
                "x-ratelimit-remaining", List.of("49"),
                "x-ratelimit-reset", List.of("60"),
                "ratelimit-policy", List.of("50;w=60"))));

        assertEquals(new RateLimitInfo(50, 49, Duration.ofSeconds(60), "50;w=60"), info);
    }

    @Test
    void headerNamesAreCaseInsensitiveAndValuesTrimmed() {
        RateLimitInfo info = RateLimitHeaders.parse(headers(Map.of(
                "X-RateLimit-Limit", List.of(" 20 "),
                "X-RateLimit-Remaining", List.of("0"),
                "X-RateLimit-Reset", List.of("28606 "),
                "RateLimit-Policy", List.of(" 20;w=86400 "))));

        assertEquals(new RateLimitInfo(20, 0, Duration.ofSeconds(28606), "20;w=86400"), info);
    }

    @Test
    void absentHeadersYieldNoWindow() {
        assertNull(RateLimitHeaders.parse(headers(Map.of())));
    }

    @Test
    void limitIsRequired() {
        assertNull(RateLimitHeaders.parse(headers(Map.of("x-ratelimit-remaining", List.of("49")))));
    }

    @Test
    void remainingIsRequired() {
        assertNull(RateLimitHeaders.parse(headers(Map.of("x-ratelimit-limit", List.of("50")))));
    }

    @Test
    void unparseableOrNegativeCountsYieldNoWindow() {
        assertNull(RateLimitHeaders.parse(headers(Map.of(
                "x-ratelimit-limit", List.of("fifty"), "x-ratelimit-remaining", List.of("49")))));
        assertNull(RateLimitHeaders.parse(headers(Map.of(
                "x-ratelimit-limit", List.of("50"), "x-ratelimit-remaining", List.of("-1")))));
    }

    @Test
    void resetAndPolicyAreOptional() {
        RateLimitInfo info = RateLimitHeaders.parse(headers(Map.of(
                "x-ratelimit-limit", List.of("50"), "x-ratelimit-remaining", List.of("49"))));

        assertEquals(new RateLimitInfo(50, 49, null, null), info);
    }

    @Test
    void unparseableResetBecomesNull() {
        RateLimitInfo info = RateLimitHeaders.parse(headers(Map.of(
                "x-ratelimit-limit", List.of("50"), "x-ratelimit-remaining", List.of("49"),
                "x-ratelimit-reset", List.of("soon"))));

        assertNull(info.reset());
        assertEquals(50, info.limit());
    }

    @Test
    void blankPolicyBecomesNull() {
        RateLimitInfo info = RateLimitHeaders.parse(headers(Map.of(
                "x-ratelimit-limit", List.of("50"), "x-ratelimit-remaining", List.of("49"),
                "ratelimit-policy", List.of("   "))));

        assertNull(info.policy());
    }

    @Test
    void classIsFinalAndNotInstantiable() throws Exception {
        assertTrue(Modifier.isFinal(RateLimitHeaders.class.getModifiers()));
        Constructor<?>[] ctors = RateLimitHeaders.class.getDeclaredConstructors();
        assertEquals(1, ctors.length);
        assertTrue(Modifier.isPrivate(ctors[0].getModifiers()));
        ctors[0].setAccessible(true);
        assertNotNull(ctors[0].newInstance());
    }

    private static HttpHeaders headers(Map<String, List<String>> map) {
        return HttpHeaders.of(map, (a, b) -> true);
    }
}
