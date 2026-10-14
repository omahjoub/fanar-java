package qa.fanar.e2e.sadiq;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import qa.fanar.core.FanarNotFoundException;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.sadiq.DeepResearchDepth;
import qa.fanar.core.sadiq.DeepResearchEvent;
import qa.fanar.core.sadiq.DeepResearchJob;
import qa.fanar.core.sadiq.DeepResearchRequest;
import qa.fanar.core.sadiq.JobStatus;
import qa.fanar.core.sadiq.ReportChunk;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.e2e.TestClients;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live battle-test of deep research's background jobs via {@link FanarClient#sadiq()} —
 * {@code startDeepResearch}, {@code deepResearchJobStream}, {@code retrieveDeepResearchJob} and
 * {@code cancelDeepResearchJob} (ADR-032) — parameterized over both codec adapters.
 *
 * <p><strong>Starting a job is gated (observed 2026-10-14).</strong> The creating case fails loudly
 * with {@link qa.fanar.core.FanarAuthorizationException} from {@code startDeepResearch}: 403
 * {@code invalid_authorization} with no rate-limit headers and no {@code x-id} — the run's own gate
 * ({@code LiveDeepResearchTest}, 2026-09-24) — so it spends nothing, and fails until the key carries
 * the {@code sadiq_deep_research} flag. The unknown-id case passes for the same key: retrieve, cancel
 * and the event stream answer 404 {@code "Not found"}, not the gate's 403, although all three carry
 * the same "requires additional authorization" note. The prediction written before the first run had
 * the gate answering first on all three.</p>
 *
 * <p>Budget: creating a job spends one of the 20 daily units of {@code Fanar-Sadiq-2 (deep research)};
 * reading, following and cancelling one spend none ("Reading a job is not metered"). One creating
 * case per codec, so 2 units per full run once granted, each a {@link DeepResearchDepth#QUICK} run of
 * 3–6 minutes; the unknown-id case is free either way. Cancelling a <em>running</em> job is not
 * exercised: it spends a unit for no report, and the case cancels its finished job instead, which
 * the spec says returns it unchanged.</p>
 *
 * <p>The creating case carries {@code @Timeout(15 min)} for the same reason as
 * {@code LiveDeepResearchTest}: the module's JUnit backstop is 5 minutes. Its offline twins are
 * {@code DeepResearchJobWireIntegrationTest} (the spec's examples through both adapters) and
 * {@code FanarClientDeepResearchJobIntegrationTest} (the resume, the guards and the retry split).
 * The gate and the 404 are dated rows in WIRE_OBSERVATIONS, "Deep research — background jobs";
 * every claim the creating case leans on beyond them is still a {@code —} row there.</p>
 *
 * <p>Skipped when {@code FANAR_API_KEY} is not set.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LiveDeepResearchJobTest {

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
    @DisplayName("§S.3 a background job runs to its report: start, follow to [DONE], retrieve, cancel when done (gated — fails until the key is upgraded)")
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void backgroundJob_followsToTheReportAndRetrievesIt(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            DeepResearchJob started = client.sadiq().startDeepResearch(PROBE);
            assertNotNull(started.id(), "a started job carries its id");
            assertFalse(started.status().isTerminal(), "a job just started has not ended, was " + started.status());
            assertEquals(PROBE.input(), started.request().input(), "the job echoes what it was asked");

            List<DeepResearchEvent> events = collect(client.sadiq().deepResearchJobStream(started.id()));
            List<ReportChunk> reports = events.stream()
                    .filter(ReportChunk.class::isInstance)
                    .map(ReportChunk.class::cast)
                    .toList();
            assertFalse(reports.isEmpty(), "the job's events must carry its report; got " + events.size() + " events");

            DeepResearchJob ended = client.sadiq().retrieveDeepResearchJob(started.id());
            assertEquals(JobStatus.COMPLETED, ended.status(), "a job whose events ended with a report has completed");
            assertNotNull(ended.result(), "a completed job carries its report");
            assertEquals(reports.getLast().report().title(), ended.result().title(),
                    "the job's result is the report its stream carried");

            DeepResearchJob afterCancel = client.sadiq().cancelDeepResearchJob(started.id());
            assertEquals(JobStatus.COMPLETED, afterCancel.status(), "cancelling an ended job returns it unchanged");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§S.3 an unknown job is not found on retrieve, cancel and stream (spends no quota)")
    void unknownJob_isNotFoundOnEveryOperation(FanarJsonCodec codec) {
        String unknown = UUID.randomUUID().toString();
        try (FanarClient client = TestClients.liveWithLogging(codec)) {
            assertThrows(FanarNotFoundException.class, () -> client.sadiq().retrieveDeepResearchJob(unknown));
            assertThrows(FanarNotFoundException.class, () -> client.sadiq().cancelDeepResearchJob(unknown));
            assertThrows(FanarNotFoundException.class, () -> client.sadiq().deepResearchJobStream(unknown));
        }
    }

    /**
     * Drain the job's events with an unbounded request and wait for the terminal signal. A failure
     * ({@code onError}) fails the test with that exception as the cause; a gone or gated job, by
     * contrast, throws from {@code deepResearchJobStream} itself before this is reached.
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
                "the job's events must end within " + RUN_BUDGET.toMinutes() + " minutes; "
                        + events.size() + " events so far");
        Throwable t = failure.get();
        if (t != null) {
            fail("following the job failed after " + events.size() + " events: " + t, t);
        }
        return events;
    }
}
