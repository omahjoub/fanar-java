package qa.fanar.obs.micrometer;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarClient;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.testsupport.CollectingSubscriber;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Micrometer adapter fed by a real, <em>retried</em> public call: {@code FanarClient.builder()
 * .observability(plugin)} → interceptor chain → scripted local server. The unit test drives
 * synthetic handles; this proves the SDK hands the adapter one stopped observation per call with
 * the {@code retry_attempt} event, the {@code fanar.retry_count} / {@code http.status_code}
 * key-values (ADR-013, ADR-025) and the {@code fanar.ratelimit.*} window of the last attempt —
 * the unbounded counters on the high-cardinality side (ADR-026) — and, for a streamed call, the
 * stream measurements on that side too (ADR-013).
 */
@Tag("integration")
class MicrometerObservabilityPluginIntegrationTest {

    private static final RetryPolicy FAST = RetryPolicy.defaults()
            .withBaseDelay(Duration.ofMillis(1))
            .withMaxDelay(Duration.ofMillis(1));

    private static final String OK = """
            {"id":"resp-1","model":"Fanar","created":1700000000,
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"hello back"}}]}
            """;

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    private final TestObservationRegistry registry = TestObservationRegistry.create();

    @Test
    void retriedCallIsOneObservationCarryingItsRetryTelemetry() {
        server.enqueue(Reply.of(503, "busy"), Reply.json(200, OK)
                        .withHeader("x-ratelimit-limit", "50")
                        .withHeader("x-ratelimit-remaining", "49")
                        .withHeader("x-ratelimit-reset", "60")
                        .withHeader("ratelimit-policy", "50;w=60"));

        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(server.baseUri())
                // no .jsonCodec(...): the Jackson 3 codec on the test module path is discovered via ServiceLoader (ADR-008)
                .retryPolicy(FAST)
                .observability(new MicrometerObservabilityPlugin(registry))
                .build()) {
            assertEquals("resp-1", client.chat().send(ping()).id());
        }

        assertEquals(2, server.hits(), "the 503 was retried once");
        TestObservationRegistryAssert.assertThat(registry)
                .hasNumberOfObservationsEqualTo(1)
                .hasObservationWithNameEqualTo("fanar.chat.send")
                .that()
                .hasBeenStopped()
                .hasEvent("retry_attempt")
                .hasLowCardinalityKeyValue("fanar.retry_count", "1")
                .hasLowCardinalityKeyValue("http.status_code", "200")
                .hasLowCardinalityKeyValue("fanar.model", ChatModel.FANAR.wireValue())
                .hasLowCardinalityKeyValue("fanar.ratelimit.limit", "50")
                .hasLowCardinalityKeyValue("fanar.ratelimit.policy", "50;w=60")
                .hasHighCardinalityKeyValue("fanar.ratelimit.remaining", "49")
                .hasHighCardinalityKeyValue("fanar.ratelimit.reset", "60");
    }

    @Test
    void streamedCallKeepsItsMeasurementsOffMetricTags() throws InterruptedException {
        server.enqueue(Reply.sse("""
                data: {"id":"r1","model":"Fanar","created":1,"choices":[{"index":0,"delta":{"content":"hello"}}]}

                data: {"id":"r1","model":"Fanar","created":1,"choices":[{"index":0,"delta":{"content":" world"}}]}

                data: [DONE]

                """));
        // The publisher records the chunk count and stops the observation in its finally block,
        // after onComplete has reached the subscriber — so wait for the stop, not the completion.
        CountDownLatch stopped = new CountDownLatch(1);
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.countDown();
            }
        });

        try (FanarClient client = FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(server.baseUri())
                .observability(new MicrometerObservabilityPlugin(registry))
                .build()) {
            CollectingSubscriber<StreamEvent> subscriber = CollectingSubscriber.unbounded();
            client.chat().stream(ping()).subscribe(subscriber);
            assertEquals(2, subscriber.awaitCompletion(Duration.ofSeconds(10)).size(),
                    "[DONE] is a sentinel, not an event");
            assertTrue(stopped.await(10, TimeUnit.SECONDS),
                    "the stream's observation stops after its terminal signal");
        }

        assertEquals(1, server.hits());
        TestObservationRegistryAssert.assertThat(registry)
                .hasNumberOfObservationsEqualTo(1)
                .hasObservationWithNameEqualTo("fanar.chat.stream")
                .that()
                .hasBeenStopped()
                .hasHighCardinalityKeyValue("fanar.stream.chunks", "2")
                .hasHighCardinalityKeyValueWithKey("fanar.stream.first_chunk_ms")
                .doesNotHaveLowCardinalityKeyValueWithKey("fanar.stream.chunks")
                .doesNotHaveLowCardinalityKeyValueWithKey("fanar.stream.first_chunk_ms")
                .hasLowCardinalityKeyValue("fanar.model", ChatModel.FANAR.wireValue());
    }

    private static ChatRequest ping() {
        return ChatRequest.builder().model(ChatModel.FANAR).addMessage(UserMessage.of("ping")).build();
    }
}
