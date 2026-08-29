package qa.fanar.core;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RateLimitInfoTest {

    @Test
    void componentsAreExposed() {
        RateLimitInfo info = new RateLimitInfo(50, 49, Duration.ofSeconds(60), "50;w=60");
        assertEquals(50, info.limit());
        assertEquals(49, info.remaining());
        assertEquals(Duration.ofSeconds(60), info.reset());
        assertEquals("50;w=60", info.policy());
        assertEquals(new RateLimitInfo(50, 49, Duration.ofSeconds(60), "50;w=60"), info);
    }

    @Test
    void resetAndPolicyMayBeAbsent() {
        RateLimitInfo info = new RateLimitInfo(20, 0, null, null);
        assertNull(info.reset());
        assertNull(info.policy());
        assertNull(info.window(), "no policy, no window");
    }

    @Test
    void rejectsNegativeLimit() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimitInfo(-1, 0, null, null));
    }

    @Test
    void rejectsNegativeRemaining() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimitInfo(50, -1, null, null));
    }

    @Test
    void rejectsNegativeReset() {
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimitInfo(50, 0, Duration.ofSeconds(-1), null));
    }

    @Test
    void windowIsParsedFromTheMatchingPolicyItem() {
        assertEquals(Duration.ofSeconds(60), new RateLimitInfo(50, 49, null, "50;w=60").window());
        assertEquals(Duration.ofDays(1), new RateLimitInfo(20, 10, null, "20;w=86400").window());
    }

    @Test
    void windowPrefersTheItemWhoseLimitMatches() {
        RateLimitInfo info = new RateLimitInfo(50, 49, null, "100;w=86400, 50;w=60");
        assertEquals(Duration.ofSeconds(60), info.window(), "the list may carry several windows");
    }

    @Test
    void windowFallsBackToTheFirstItemCarryingOne() {
        assertEquals(Duration.ofSeconds(1), new RateLimitInfo(20, 0, null, "10;w=1, 30;w=2").window(),
                "no item matches limit 20 — the first window wins");
        assertEquals(Duration.ofSeconds(60), new RateLimitInfo(20, 0, null, "x;w=60").window(),
                "an unparseable item limit still yields its window as the fallback");
    }

    @Test
    void windowIgnoresOtherParametersAndCase() {
        assertEquals(Duration.ofSeconds(60), new RateLimitInfo(50, 0, null, "50;q=1;W=60").window());
    }

    @Test
    void windowIsAbsentWhenNoItemCarriesOne() {
        assertNull(new RateLimitInfo(50, 0, null, "50").window(), "no w= parameter");
        assertNull(new RateLimitInfo(50, 0, null, "50;w=abc").window(), "unparseable window");
        assertNull(new RateLimitInfo(50, 0, null, "50;w=-1").window(), "negative window");
        assertNull(new RateLimitInfo(50, 0, null, "").window(), "empty policy");
    }
}
