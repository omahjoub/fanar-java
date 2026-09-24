package qa.fanar.e2e.sadiq;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import qa.fanar.core.FanarClient;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.DoneChunk;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.sadiq.DeepResearchDepth;
import qa.fanar.core.sadiq.DeepResearchEvent;
import qa.fanar.core.sadiq.DeepResearchReport;
import qa.fanar.core.sadiq.DeepResearchRequest;
import qa.fanar.core.sadiq.ReportChunk;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live battle-test of {@code POST /v1/sadiq/deep-research} via {@link FanarClient#sadiq()} —
 * the stream ({@code deepResearchStream}) and the blocking collector ({@code deepResearch}) —
 * parameterized over both codec adapters.
 *
 * <p><strong>Gated — observed 2026-09-24.</strong> The endpoint requires the
 * {@code sadiq_deep_research} feature flag on the API key ("requires additional authorization and
 * is not allowed by default"), which the standard key does not carry: all four cases answered
 * <strong>HTTP 403</strong>, envelope verbatim {@code {"error": {"code": "invalid_authorization",
 * "message": "Invalid authorization", "status": 403, "param": null, "type": null}}} — the same
 * shape the endpoint gate of {@code POST /v1/sadiq/validate} answers with (2026-09-15) — in
 * 605–928 ms, with no rate-limit headers and no {@code x-id}, {@code fanar.retry_count=0}. So these
 * cases fail with {@link qa.fanar.core.FanarAuthorizationException} until the key is upgraded; that
 * failure is the desired diagnostic signal, not a flake. The gate throws from the call itself,
 * before anything is subscribed, for the stream and the blocking variant alike; the request body
 * ({@code stream}, {@code model}, {@code input}, {@code depth}, {@code web_search}) is accepted up
 * to the authorization check, so a 403 here is not a wire-format problem. Both routings — the
 * endpoint gate and the 422 model gate {@code Fanar-Sadiq-2} answers with on chat — are proved
 * against a scripted server by {@code FanarClientDeepResearchIntegrationTest}, which also pins that
 * a deep-research call is never retried; see WIRE_OBSERVATIONS, "Deep research".</p>
 *
 * <p>Budget: 20 requests per day for {@code Fanar-Sadiq-2 (deep research)}, consumed on
 * admission; 4 calls per full run once granted (2 methods × 2 codecs), each 3–6 minutes at
 * {@link DeepResearchDepth#QUICK} — start full runs ≥ 24 h apart. While the gate holds they are
 * rejected before admission and carry no rate-limit headers, so they consume nothing (observed
 * 2026-09-24).</p>
 *
 * <p>Each case carries {@code @Timeout(15 min)}: the module's JUnit backstop is 5 minutes
 * ({@code e2e/pom.xml}) and a QUICK run alone takes 3–6. The client's 60 s request timeout is no
 * obstacle — it bounds only the wait for the server to admit the run (its response headers), never
 * the run itself. The stream case drains the publisher with an inline subscriber and a latch so
 * that a mid-run failure fails the case with the real exception attached; the offline twin,
 * {@code DeepResearchWireIntegrationTest}, is where the same events are proved with both codecs
 * against a scripted server.</p>
 *
 * <p>Skipped when {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LiveDeepResearchTest {

    /** The spec's own example topic, at the cheapest depth and without web search. */
    private static final DeepResearchRequest PROBE = new DeepResearchRequest(
            ChatModel.FANAR_SADIQ_2,
            "The importance of seeking knowledge in Islam",
            DeepResearchDepth.QUICK,
            false);

    /** Generous against the spec's 3–6 minutes for a QUICK run; the 15-minute timeout backstops it. */
    private static final Duration RUN_BUDGET = Duration.ofMinutes(12);

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§S.2 deepResearchStream delivers progress, then the report once, then done (gated — fails until the key is upgraded)")
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void deepResearchStream_deliversProgressThenTheReport(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            List<DeepResearchEvent> events = collect(client.sadiq().deepResearchStream(PROBE));

            assertFalse(events.isEmpty(), "stream must emit at least one event");
            assertTrue(events.stream().anyMatch(e -> e instanceof ProgressChunk),
                    "a run must announce at least one research pass as a ProgressChunk");

            List<ReportChunk> reports = events.stream()
                    .filter(ReportChunk.class::isInstance)
                    .map(ReportChunk.class::cast)
                    .toList();
            assertEquals(1, reports.size(),
                    "the finished report must arrive exactly once, got " + reports.size());
            DeepResearchReport report = reports.getFirst().report();
            assertTrue(report.title() != null || report.markdown() != null,
                    "the report must carry a title or its markdown rendering");

            DeepResearchEvent last = events.getLast();
            assertInstanceOf(DoneChunk.class, last,
                    "last event must be DoneChunk, got " + last.getClass().getSimpleName());
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§S.2 deepResearch() returns the report the stream carried (gated — fails until the key is upgraded)")
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void deepResearch_returnsTheReport(FanarJsonCodec codec) {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            DeepResearchReport report = client.sadiq().deepResearch(PROBE);

            assertTrue(report.title() != null || report.markdown() != null,
                    "the report must carry a title or its markdown rendering");
            assertNotNull(report.sections(), "sections must be present (empty when the server sends none)");
        }
    }

    /**
     * Drain the run with an unbounded request and wait for its terminal signal. A mid-run transport
     * failure ({@code onError}) fails the test with that exception as the cause; the gate, by
     * contrast, throws from {@code deepResearchStream} itself before this is ever reached.
     */
    private static List<DeepResearchEvent> collect(Flow.Publisher<DeepResearchEvent> publisher)
            throws InterruptedException {
        List<DeepResearchEvent> events = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch terminal = new CountDownLatch(1);

        publisher.subscribe(new Flow.Subscriber<DeepResearchEvent>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(DeepResearchEvent item) { events.add(item); }
            @Override public void onError(Throwable t) { failure.set(t); terminal.countDown(); }
            @Override public void onComplete() { terminal.countDown(); }
        });

        assertTrue(terminal.await(RUN_BUDGET.toMillis(), TimeUnit.MILLISECONDS),
                "the run must terminate within " + RUN_BUDGET.toMinutes() + " minutes; "
                        + events.size() + " events so far");
        Throwable t = failure.get();
        if (t != null) {
            fail("the run failed on the wire after " + events.size() + " events: " + t, t);
        }
        return events;
    }
}
