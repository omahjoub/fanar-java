package qa.fanar.e2e.transport;

import java.net.http.HttpHeaders;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import qa.fanar.core.FanarClient;
import qa.fanar.core.chat.ChatResponse;
import qa.fanar.core.spi.FanarObservationAttributes;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;
import qa.fanar.e2e.Probes;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live pin of the transport-level rate-limit header contract documented in the 2026-08-27 spec
 * refresh: {@code x-ratelimit-limit} / {@code x-ratelimit-remaining} / {@code x-ratelimit-reset}
 * plus {@code ratelimit-policy} as {@code limit;w=seconds}. The headers are read below the codec
 * layer, so — unlike the per-domain {@code Live*Test} classes — this test runs on a single codec:
 * a second parameterization would spend a counted request to observe the same bytes.
 *
 * <p>Observed 2026-08-27 by curl and 2026-08-28 through this test (standard limited key,
 * {@code Fanar} chat): every 2xx chat response carries all four headers, lowercase —
 * {@code x-ratelimit-limit: 50}, {@code x-ratelimit-remaining} decrementing per counted request
 * (49 → 48 on consecutive calls), {@code x-ratelimit-reset: 60},
 * {@code ratelimit-policy: 50;w=60}. Caveats observed the same days:
 * the headers are per-model-quota only ({@code GET /v1/models} and 401 responses carry none), and
 * the spec omits them entirely for unlimited-quota keys — if this test starts failing on missing
 * headers, check whether the key was upgraded before suspecting the SDK. The windows are sliding:
 * {@code x-ratelimit-reset} counts down to the moment the <em>oldest</em> counted request ages out
 * (2026-08-29: 60 → 59 on consecutive calls, jumping back up when a request left the window;
 * 28607 → 28606 on the exhausted per-day window) — asserted to parse, not to count down; the
 * dated record is {@code docs/WIRE_OBSERVATIONS.md}.</p>
 *
 * <p>Observed 2026-08-28 but deliberately not asserted here, because provoking it burns a 20/day
 * budget: an exhausted {@code Fanar-Aura-TTS-2} window ({@code ratelimit-policy: 20;w=86400},
 * {@code x-ratelimit-remaining: 0}) answers 429 with envelope code {@code rate_limit_reached},
 * {@code retry-after} equal to {@code x-ratelimit-reset} (28606&nbsp;s), and no {@code x-id}. The
 * retry interceptor surfaced it immediately — {@code fanar.retry_count=0}, no sleep — per
 * ADR-025.</p>
 *
 * <p>Since ADR-026 the same counted request also proves the SDK's typed exposure: the retry
 * boundary publishes the four headers as the {@code fanar.ratelimit.*} observation attributes,
 * asserted here through a recording plugin — no extra budget.</p>
 *
 * <p>Skipped when {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LiveRateLimitHeadersTest {

    /** One {@code limit;w=seconds} item. The header is a list, so the pin is "contains", not "equals". */
    private static final Pattern POLICY_ITEM = Pattern.compile("(\\d+);w=(\\d+)");

    @Test
    @DisplayName("§T.1 2xx chat response carries the documented rate-limit headers — and the SDK publishes them")
    void success_carriesRateLimitHeaders() {
        AtomicReference<HttpHeaders> captured = new AtomicReference<>();
        RecordingPlugin recording = new RecordingPlugin();
        Interceptor capture = (request, chain) -> {
            var response = chain.proceed(request);
            captured.set(response.headers());
            return response;
        };
        try (FanarClient client = TestClients.liveWithLogging(new Jackson3FanarJsonCodec(), recording, capture)) {
            ChatResponse r = client.chat().send(Probes.ping());
            assertNotNull(r.id(), "response id must be present");
        }
        HttpHeaders headers = captured.get();
        assertNotNull(headers, "capturing interceptor must have seen the response");

        long limit = requiredLong(headers, "x-ratelimit-limit");
        long remaining = requiredLong(headers, "x-ratelimit-remaining");
        long reset = requiredLong(headers, "x-ratelimit-reset");
        assertTrue(remaining < limit,
                "remaining (" + remaining + ") must sit below limit (" + limit
                        + ") right after a counted request (observed 49 → 48 against 50)");

        String policy = headers.firstValue("ratelimit-policy").orElse(null);
        assertNotNull(policy, "ratelimit-policy header must be present");
        Matcher item = POLICY_ITEM.matcher(policy);
        assertTrue(item.find(), "ratelimit-policy must contain a 'limit;w=seconds' item, got: " + policy);
        assertEquals(limit, Long.parseLong(item.group(1)),
                "the policy's limit must match x-ratelimit-limit, got: " + policy);
        assertTrue(Long.parseLong(item.group(2)) > 0, "the window must be positive, got: " + policy);

        // ADR-026: the retry boundary publishes the same headers as observation attributes.
        assertEquals(limit, recording.attributes.get(FanarObservationAttributes.FANAR_RATELIMIT_LIMIT),
                "fanar.ratelimit.limit must mirror x-ratelimit-limit: " + recording.attributes);
        assertEquals(remaining, recording.attributes.get(FanarObservationAttributes.FANAR_RATELIMIT_REMAINING));
        assertEquals(reset, recording.attributes.get(FanarObservationAttributes.FANAR_RATELIMIT_RESET));
        assertEquals(policy, recording.attributes.get(FanarObservationAttributes.FANAR_RATELIMIT_POLICY));
    }

    /** Records every attribute of every operation — one map, last write wins. */
    private static final class RecordingPlugin implements ObservabilityPlugin, ObservationHandle {
        final Map<String, Object> attributes = new ConcurrentHashMap<>();

        @Override public ObservationHandle start(String operationName) { return this; }
        @Override public ObservationHandle attribute(String key, Object value) { attributes.put(key, value); return this; }
        @Override public ObservationHandle event(String name) { return this; }
        @Override public ObservationHandle error(Throwable throwable) { return this; }
        @Override public ObservationHandle child(String operationName) { return this; }
        @Override public Map<String, String> propagationHeaders() { return Map.of(); }
        @Override public void close() { }
    }

    /** Assert the header {@code name} is present and a non-negative integer; return its value. */
    private static long requiredLong(HttpHeaders headers, String name) {
        String value = headers.firstValue(name).orElse(null);
        assertNotNull(value, name + " header must be present (the 2026-08-27 spec omits the "
                + "rate-limit headers only for unlimited-quota keys — check the key before "
                + "suspecting the SDK)");
        long parsed = assertDoesNotThrow(() -> Long.parseLong(value.trim()),
                name + " must be an integer, got: " + value);
        assertTrue(parsed >= 0, name + " must be >= 0, got: " + parsed);
        return parsed;
    }
}
