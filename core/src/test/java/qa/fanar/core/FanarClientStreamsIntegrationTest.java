package qa.fanar.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.ChoiceToken;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Streams} through the <em>public</em> API: {@code FanarClient.builder()} →
 * {@code chat().stream()} → interceptor chain → real JDK transport → a local
 * {@link ScriptedHttpServer}, consumed as a {@code Stream} and abandoned early.
 *
 * <p>This is the seam {@code StreamsTest} cannot see. That test drives the bridge against a
 * hand-rolled {@code Flow.Publisher}, which has no HTTP response to release; the guarantee
 * {@code Streams} leads with — closing cancels the subscription and releases the underlying
 * response — only has meaning against {@code SseStreamPublisher}, and the wiring that carries it
 * ({@code Stream.close()} → {@code cancel()} → {@code Session.cancel()} → close the body) is four
 * links long with a unit test at each end and nothing across the middle (ADR-005, ADR-006).</p>
 *
 * <p>It is also the only test where the two halves of the demand protocol meet their real
 * counterparts: {@code Streams} requests from the <em>consumer</em> thread inside
 * {@code tryAdvance} while the publisher's producer thread is parked in {@code awaitDemand}.</p>
 *
 * <p>The last two cases pin what {@code requestTimeout} means for a stream (ADR-007): it bounds
 * the wait for the response headers and nothing after them. A body that takes longer than the
 * timeout is still delivered in full — on JDK 26 the JDK's own request timer would have ended it,
 * which is why the transport no longer uses that timer — and headers that take longer than the
 * timeout fail the call with a {@link FanarTransportException} after one hit.</p>
 */
@Tag("integration")
class FanarClientStreamsIntegrationTest {

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    @Test
    void closingTheStreamEarlyCancelsTheSubscriptionAndReleasesTheResponse() throws Exception {
        // Three frames offered, one consumed: findFirst short-circuits, which is exactly the
        // case a for-each over an Iterable could not clean up after.
        server.enqueue(Reply.sse("data: {}\n\ndata: {}\n\ndata: {}\n\n"));
        CountDownLatch observationClosed = new CountDownLatch(1);

        try (FanarClient client = client(observationClosed)) {
            Optional<StreamEvent> first;
            try (Stream<StreamEvent> stream = Streams.toStream(client.chat().stream(ping()))) {
                first = stream.findFirst();
            }
            assertTrue(first.isPresent(), "the first event is delivered before the stream is closed");

            // The publisher closes the observation in its finally block, after closing the body.
            // The latch firing is therefore the observable proof that the producer exited and the
            // response was released — not left parked on a half-read body until GC.
            assertTrue(observationClosed.await(5, TimeUnit.SECONDS),
                    "closing the stream must cancel the subscription and release the response");
        }

        assertEquals(1, server.hits(), "one handshake, and no reconnect after cancellation");
    }

    @Test
    void aFullyConsumedStreamDeliversEveryEventAndCloses() throws Exception {
        // The other end of the same wiring: no cancellation, so the producer runs to [DONE] and
        // the terminal signal closes the observation on the completion path instead.
        server.enqueue(Reply.sse("data: {}\n\ndata: {}\n\ndata: [DONE]\n\n"));
        CountDownLatch observationClosed = new CountDownLatch(1);

        try (FanarClient client = client(observationClosed)) {
            try (Stream<StreamEvent> stream = Streams.toStream(client.chat().stream(ping()))) {
                assertEquals(2, stream.toList().size(), "[DONE] is a sentinel, not an event");
            }
            assertTrue(observationClosed.await(5, TimeUnit.SECONDS),
                    "a completed stream closes its observation too");
        }

        assertEquals(1, server.hits());
    }

    @Test
    void aBodySlowerThanTheRequestTimeoutIsStillDeliveredInFull() throws Exception {
        // Headers at once, then the body held back for three times the request timeout. The
        // timeout must not fire once the headers are in: every frame arrives and the stream
        // completes normally. (This is the JDK 26 regression guard — see the class Javadoc.)
        Duration requestTimeout = Duration.ofMillis(500);
        server.enqueue(Reply.sse("data: {}\n\ndata: {}\n\ndata: [DONE]\n\n")
                .withBodyDelay(requestTimeout.multipliedBy(3)));
        CountDownLatch observationClosed = new CountDownLatch(1);

        try (FanarClient client = client(observationClosed, requestTimeout)) {
            try (Stream<StreamEvent> stream = Streams.toStream(client.chat().stream(ping()))) {
                assertEquals(2, stream.toList().size(),
                        "both frames arrive although the body outlasted the request timeout");
            }
            assertTrue(observationClosed.await(5, TimeUnit.SECONDS),
                    "the stream completes normally and closes its observation");
        }

        assertEquals(1, server.hits());
    }

    @Test
    void headersSlowerThanTheRequestTimeoutFailTheCall() {
        // The other half of the same definition: nothing arrives within the timeout, so the
        // handshake fails as a transport error after exactly one request. Retries are off so the
        // hit count states the timeout's own behaviour, not the retry policy's.
        Duration requestTimeout = Duration.ofMillis(300);
        server.enqueue(Reply.sse("data: {}\n\ndata: [DONE]\n\n").withHeaderDelay(requestTimeout.multipliedBy(5)));

        try (FanarClient client = client(new CountDownLatch(1), requestTimeout)) {
            FanarTransportException ex = assertThrows(FanarTransportException.class,
                    () -> client.chat().stream(ping()));
            assertTrue(ex.getMessage().startsWith("HTTP request timed out"),
                    "Expected the timeout message, got: " + ex.getMessage());
        }

        assertEquals(1, server.hits(), "one handshake, given up on the client side");
    }

    // --- helpers -----------------------------------------------------------------------------

    private FanarClient client(CountDownLatch observationClosed) {
        return client(observationClosed, Duration.ofSeconds(5));
    }

    private FanarClient client(CountDownLatch observationClosed, Duration requestTimeout) {
        return FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(server.baseUri())
                .jsonCodec(cannedCodec())
                .observability(latchingObservability(observationClosed))
                .retryPolicy(RetryPolicy.disabled())
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(requestTimeout)
                .build();
    }

    /** Counts down when the streaming observation reaches its terminal signal. */
    private static ObservabilityPlugin latchingObservability(CountDownLatch closed) {
        return operationName -> new ObservationHandle() {
            @Override public ObservationHandle attribute(String key, Object value) { return this; }
            @Override public ObservationHandle event(String name) { return this; }
            @Override public ObservationHandle error(Throwable error) { return this; }
            @Override public ObservationHandle child(String name) { return this; }
            @Override public Map<String, String> propagationHeaders() { return Map.of(); }
            @Override public void close() { closed.countDown(); }
        };
    }

    /**
     * Every frame decodes to the same token. The decoder asks for {@code Map} first to classify
     * the payload; an empty map classifies as {@link TokenChunk}, which is what this test wants.
     */
    private static FanarJsonCodec cannedCodec() {
        TokenChunk canned = new TokenChunk("c_1", 0L, "Fanar", List.of(new ChoiceToken(0, null, "hi")));
        return new FanarJsonCodec() {
            public <T> T decode(InputStream in, Class<T> type) throws IOException {
                in.readAllBytes();
                return type.cast(type == Map.class ? Map.of() : canned);
            }
            public void encode(OutputStream out, Object value) throws IOException {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static ChatRequest ping() {
        return ChatRequest.builder().model(ChatModel.FANAR).addMessage(UserMessage.of("ping")).build();
    }
}
