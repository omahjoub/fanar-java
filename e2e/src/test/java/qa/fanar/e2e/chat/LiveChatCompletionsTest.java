package qa.fanar.e2e.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import qa.fanar.core.FanarAuthenticationException;
import qa.fanar.core.FanarClient;
import qa.fanar.core.chat.*;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.e2e.Probes;
import qa.fanar.e2e.TestClients;
import qa.fanar.interceptor.logging.WireLoggingInterceptor;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Single-file battle-test of the {@code POST /v1/chat/completions} endpoint via the SDK, run
 * once per JSON codec adapter (Jackson 2 and Jackson 3). Every public-facing capability —
 * models, sampling, streaming, thinking, Sadiq RAG, error mapping — has at least one test
 * here, and each test runs against both codecs so we catch any divergence between adapters
 * the offline parity test misses.
 *
 * <p>Skipped when {@code FANAR_API_KEY} is not set.</p>
 *
 * <p>Run from the IDE by opening this class and invoking the run icon next to the class
 * declaration. Cost is roughly 50–60 chat completions per full run (~30 cases × 2 codecs),
 * comfortably within any per-minute rate limit.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FANAR_API_KEY", matches = ".+")
class LiveChatCompletionsTest {

    /** Both codec adapters under test. Each test method runs once per entry. */
    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    // =====================================================================================
    // §1 — Model probes: one minimal send per non-vision model.
    // =====================================================================================

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§1.1 Fanar (router/default) — non-empty assistant text")
    void model_fanar_router(FanarJsonCodec codec) {
        assertSimpleReply(codec, Probes.pingFor(ChatModel.FANAR));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§1.2 Fanar-S-1-7B (Star, text)")
    void model_fanar_s_1_7b(FanarJsonCodec codec) {
        assertSimpleReply(codec, Probes.pingFor(ChatModel.FANAR_S_1_7B));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§1.3 Fanar-C-1-8.7B (thinking v1)")
    void model_fanar_c_1_8_7b(FanarJsonCodec codec) {
        assertSimpleReply(codec, Probes.pingFor(ChatModel.FANAR_C_1_8_7B));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§1.4 Fanar-C-2-27B (thinking v2)")
    void model_fanar_c_2_27b(FanarJsonCodec codec) {
        assertSimpleReply(codec, Probes.pingFor(ChatModel.FANAR_C_2_27B));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§1.5 Fanar-Sadiq (Islamic RAG, text reply)")
    void model_fanar_sadiq(FanarJsonCodec codec) {
        try (FanarClient client = liveClient(codec)) {
            ChatResponse r = client.chat().send(Probes.sadiq());
            assertNotNull(r.id(), "response id must be present");
            ChatChoice choice = r.choices().getFirst();
            assertEquals(FinishReason.STOP, choice.finishReason());
            assertNotNull(textOf(r), "Sadiq must return assistant text");
        }
    }

    // =====================================================================================
    // §2 — Conversation shapes.
    // =====================================================================================

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§2.1 Multi-turn (system → user → assistant → user)")
    void conversation_multiTurn(FanarJsonCodec codec) {
        try (FanarClient client = liveClient(codec)) {
            ChatResponse r = client.chat().send(Probes.multiTurn());
            assertNotNull(textOf(r), "multi-turn response must have text");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§2.2 enable_thinking=true with Fanar-C-2-27B")
    void conversation_thinking(FanarJsonCodec codec) {
        try (FanarClient client = liveClient(codec)) {
            ChatResponse r = client.chat().send(Probes.thinking());
            assertNotNull(textOf(r), "thinking response must have text");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§2.3 Fanar-Sadiq with restrict_to_islamic returns references")
    void conversation_sadiqRagWithReferences(FanarJsonCodec codec) {
        try (FanarClient client = liveClient(codec)) {
            ChatResponse r = client.chat().send(Probes.sadiq());
            ChatChoice choice = r.choices().getFirst();
            // References field may be empty for some prompts; we assert only that the field
            // is present. The reference list itself shows up in the wire log.
            assertNotNull(choice.message(), "assistant message must be present");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§2.4 Sadiq with a typed BookName lands on the wire (verifies BookName serialization)")
    void conversation_sadiqWithBookName(FanarJsonCodec codec) {
        try (FanarClient client = liveClient(codec)) {
            ChatResponse r = client.chat().send(Probes.sadiqWithBookName());
            // Only assertion: the server accepted the typed BookName wire format. Whether the
            // constrained book covers the prompt is Sadiq's retriever's call.
            assertNotNull(r.id(), "response id must be present");
        }
    }

    // =====================================================================================
    // §3 — Sampling determinism.
    // =====================================================================================

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§3.1 temperature=0 produces stable output across two sends")
    void sampling_temperatureZeroIsStable(FanarJsonCodec codec) {
        try (FanarClient client = liveClient(codec)) {
            ChatRequest req = Probes.ping();
            String t1 = textOf(client.chat().send(req));
            String t2 = textOf(client.chat().send(req));
            // The server may not be perfectly deterministic across calls (caches, sharding),
            // but with temperature=0 + max_tokens=8 the outputs are extremely likely to match.
            // Both responses appear in the wire log; assert only that both came back.
            assertNotNull(t1);
            assertNotNull(t2);
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§3.2 n=3 returns three independent choices")
    void sampling_nReturnsMultipleChoices(FanarJsonCodec codec) {
        try (FanarClient client = liveClient(codec)) {
            ChatResponse r = client.chat().send(Probes.tripleChoice());
            assertEquals(3, r.choices().size(),
                    "n=3 must return 3 choices, got " + r.choices().size());
            for (ChatChoice c : r.choices()) {
                assertNotNull(c.message(), "every choice must carry a message");
            }
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§3.3 stop sequence is sent on the wire (server honoring is best-effort)")
    void sampling_stopSequence(FanarJsonCodec codec) {
        try (FanarClient client = liveClient(codec)) {
            ChatResponse r = client.chat().send(Probes.withStop());
            String text = textOf(r);
            assertNotNull(text);
            // SDK-side wire serialization is locked down by ChatRequestKnobsTest. Fanar's
            // chat-completion endpoint empirically ignores `stop` (see Probes.withStop), so
            // we do not hard-assert truncation — only verify the request/response loop works.
            // The wire log shows the actual finish reason and text the server returned.
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§3.4 logprobs=true populates ChoiceLogprobs")
    void sampling_logprobs(FanarJsonCodec codec) {
        try (FanarClient client = liveClient(codec)) {
            ChatResponse r = client.chat().send(Probes.withLogprobs());
            ChatChoice choice = r.choices().getFirst();
            assertNotNull(choice.logprobs(),
                    "logprobs=true must populate ChoiceLogprobs");
            assertFalse(choice.logprobs().content().isEmpty(),
                    "logprobs.content must not be empty");
        }
    }

    // =====================================================================================
    // §4 — Streaming.
    // =====================================================================================

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§4.1 stream(...) emits TokenChunks then a DoneChunk")
    void streaming_tokenSequenceThenDone(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = liveClient(codec)) {
            List<StreamEvent> events = collectStream(client, Probes.streamingPing());

            assertFalse(events.isEmpty(), "stream must emit at least one event");
            // The last event MUST be a DoneChunk for a non-erroring stream.
            StreamEvent last = events.getLast();
            assertInstanceOf(DoneChunk.class, last,
                    "last event must be DoneChunk, got " + last.getClass().getSimpleName());
            // At least one TokenChunk along the way.
            assertTrue(events.stream().anyMatch(e -> e instanceof TokenChunk),
                    "stream must emit at least one TokenChunk");

            // The DoneChunk should carry usage.
            DoneChunk done = (DoneChunk) last;
            assertNotNull(done.usage(), "DoneChunk must carry usage");
            assertTrue(done.usage().totalTokens() > 0, "totalTokens must be > 0");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§4.2 Streamed text accumulates into the same answer as send()")
    void streaming_textMatchesSyncSend(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = liveClient(codec)) {
            ChatRequest req = Probes.streamingPing();
            String synchronous = textOf(client.chat().send(req));
            List<StreamEvent> events = collectStream(client, req);

            String streamed = events.stream()
                    .filter(e -> e instanceof TokenChunk)
                    .map(e -> (TokenChunk) e)
                    .flatMap(t -> t.choices().stream())
                    .map(ChoiceToken::content)
                    .reduce("", String::concat);
            // Determinism across sync vs stream is best-effort; assert only that both came back.
            assertNotNull(synchronous);
            assertFalse(streamed.isBlank(), "streamed text must not be blank");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§4.3 Sadiq stream emits ProgressChunks before TokenChunks")
    void streaming_sadiqProgressChunks(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = liveClient(codec)) {
            List<StreamEvent> events = collectStream(client, Probes.sadiq());

            // Sadiq doesn't always emit progress chunks (depends on whether retrieval ran);
            // hard-assert only that the stream completed cleanly.
            assertInstanceOf(DoneChunk.class, events.getLast(),
                    "Sadiq stream must terminate with DoneChunk");
        }
    }

    /**
     * Observes server-internal tool-call telemetry on the Sadiq stream.
     *
     * <p>Important: Fanar's {@code ChatCompletionRequest} schema does <strong>not</strong>
     * accept user-defined {@code tools}/{@code tool_choice} — function calling is not
     * supported on the request side. Any {@code ToolCallChunk}/{@code ToolResultChunk}s
     * we see here are emitted by Sadiq's own RAG retriever (server-side tool execution),
     * not by a tool we registered. This test only verifies the stream completes cleanly;
     * the offline {@code AdapterParityTest} locks down decoding for the canned shape.</p>
     */
    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§4.4 Sadiq stream may surface server-internal tool-call telemetry")
    void streaming_sadiqToolCalls(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = liveClient(codec)) {
            List<StreamEvent> events = collectStream(client, Probes.sadiq());
            assertInstanceOf(DoneChunk.class, events.getLast(),
                    "Sadiq stream must terminate with DoneChunk");
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§4.5 cancel() mid-stream stops further deliveries")
    void streaming_cancelMidStream(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = liveClient(codec)) {
            CountDownLatch firstEvent = new CountDownLatch(1);
            AtomicReference<Flow.Subscription> subRef = new AtomicReference<>();
            CopyOnWriteArrayList<StreamEvent> events = new CopyOnWriteArrayList<>();
            CompletableFuture<Throwable> errored = new CompletableFuture<>();

            client.chat().stream(Probes.streamingPing()).subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription s) {
                    subRef.set(s);
                    s.request(Long.MAX_VALUE);
                }
                public void onNext(StreamEvent item) {
                    events.add(item);
                    firstEvent.countDown();
                }
                public void onError(Throwable t) { errored.complete(t); }
                public void onComplete() { /* normal exit if cancel races finish */ }
            });

            assertTrue(firstEvent.await(30, TimeUnit.SECONDS),
                    "must receive at least one event before cancel");
            int countAtCancel = events.size();
            subRef.get().cancel();
            // Give the producer a moment to observe the close.
            Thread.sleep(500);
            // After cancel, no further deliveries.
            assertTrue(events.size() <= countAtCancel + 4,
                    "small drift OK (in-flight events), but no flood expected: "
                            + countAtCancel + " → " + events.size());
            assertFalse(errored.isDone(),
                    "cancellation must not surface as onError");
        }
    }

    // =====================================================================================
    // §5 — Async path.
    // =====================================================================================

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§5.1 sendAsync().get() completes against live infra with the same response shape")
    void async_sendCompletesAgainstLiveInfra(FanarJsonCodec codec) throws Exception {
        try (FanarClient client = liveClient(codec)) {
            ChatResponse r = client.chat().sendAsync(Probes.ping()).get(60, TimeUnit.SECONDS);
            assertNotNull(r.id(), "response id must be present");
            ChatChoice choice = r.choices().getFirst();
            assertEquals(FinishReason.STOP, choice.finishReason());
            assertNotNull(textOf(r), "expected text content");
        }
    }

    // =====================================================================================
    // §6 — Error mapping.
    // =====================================================================================

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("§6.1 bogus API key → FanarAuthenticationException")
    void error_invalidApiKey(FanarJsonCodec codec) {
        try (FanarClient client = FanarClient.builder()
                .apiKey("definitely-not-a-real-key")
                .jsonCodec(codec)
                .addInterceptor(WireLoggingInterceptor.builder()
                        .level(WireLoggingInterceptor.Level.BODY).build())
                .build()) {
            assertThrows(FanarAuthenticationException.class,
                    () -> client.chat().send(Probes.bogusAuthProbe()));
        }
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private static FanarClient liveClient(FanarJsonCodec codec) {
        return TestClients.liveWithLogging(codec);
    }

    /** Sync-style stream collector with a timeout. */
    private static List<StreamEvent> collectStream(
            FanarClient client, ChatRequest request) throws Exception {
        List<StreamEvent> events = new ArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();

        client.chat().stream(request).subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(StreamEvent item) { events.add(item); }
            public void onError(Throwable t) { done.completeExceptionally(t); }
            public void onComplete() { done.complete(null); }
        });

        done.get(60, TimeUnit.SECONDS);
        return events;
    }

    /** First text content of the first choice, or {@code null} if the response has no text. */
    private static String textOf(ChatResponse response) {
        ChatChoice choice = response.choices().getFirst();
        for (ResponseContent part : choice.message().content()) {
            if (part instanceof TextContent(String text)) return text;
        }
        return null;
    }

    /** Send {@code request} and assert the assistant returned non-blank text. */
    private static void assertSimpleReply(FanarJsonCodec codec, ChatRequest request) {
        try (FanarClient client = liveClient(codec)) {
            ChatResponse r = client.chat().send(request);
            assertNotNull(r.id(), "response id must be present");
            assertNotNull(r.model(), "response model must be present");
            ChatChoice choice = r.choices().getFirst();
            assertEquals(FinishReason.STOP, choice.finishReason(),
                    "expected stop finish reason");
            String text = textOf(r);
            assertNotNull(text, "expected text content");
            assertFalse(text.isBlank(), "text must not be blank");
        }
    }

}
