package qa.fanar.obs.micrometer;

import java.time.Duration;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarClient;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Micrometer adapter fed by a real, <em>retried</em> public call: {@code FanarClient.builder()
 * .observability(plugin)} → interceptor chain → scripted local server. The unit test drives
 * synthetic handles; this proves the SDK hands the adapter one stopped observation per call with
 * the {@code retry_attempt} event and the {@code fanar.retry_count} / {@code http.status_code}
 * key-values (ADR-013, ADR-025).
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
        server.enqueue(Reply.of(503, "busy"), Reply.json(200, OK));

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
                .hasObservationWithNameEqualTo("fanar.chat")
                .that()
                .hasBeenStopped()
                .hasEvent("retry_attempt")
                .hasLowCardinalityKeyValue("fanar.retry_count", "1")
                .hasLowCardinalityKeyValue("http.status_code", "200")
                .hasLowCardinalityKeyValue("fanar.model", ChatModel.FANAR.wireValue());
    }

    private static ChatRequest ping() {
        return ChatRequest.builder().model(ChatModel.FANAR).addMessage(UserMessage.of("ping")).build();
    }
}
