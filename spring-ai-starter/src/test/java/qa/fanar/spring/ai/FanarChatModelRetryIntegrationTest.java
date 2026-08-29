package qa.fanar.spring.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import qa.fanar.core.FanarClient;
import qa.fanar.core.FanarRateLimitException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Spring AI adapter on top of a retry-enabled client: {@code FanarChatModel.call()} →
 * {@code FanarClient} → interceptor chain → real JDK transport → a scripted local server.
 * {@code FanarChatModelTest} proves the mapping with retries disabled; this proves the adapter
 * neither hides a retried transient failure nor loses the {@code Retry-After} hint (ADR-021,
 * ADR-025). The image / speech / transcription adapters share the same client chain and are
 * not repeated here.
 */
@Tag("integration")
class FanarChatModelRetryIntegrationTest {

    private static final RetryPolicy FAST = RetryPolicy.defaults()
            .withBaseDelay(Duration.ofMillis(1))
            .withMaxDelay(Duration.ofMillis(1));

    private static final String OK = """
            {"id":"resp-1","model":"Fanar","created":1700000000,
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"hello back"}}]}
            """;

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    @Test
    void callRetriesATransientFailureThroughTheClientChain() {
        server.enqueue(Reply.of(503, "busy"), Reply.json(200, OK));

        try (FanarClient client = client(FAST)) {
            ChatResponse response = new FanarChatModel(client, ChatModel.FANAR).call(prompt());
            assertThat(response.getResult().getOutput().getText()).isEqualTo("hello back");
        }

        assertThat(server.hits()).as("the 503 was retried once").isEqualTo(2);
    }

    @Test
    void callSurfacesTheRetryAfterHintAboveTheCeiling() {
        server.enqueue(Reply.of(429, "come back later", Map.of("Retry-After", "7200")));

        try (FanarClient client = client(RetryPolicy.defaults())) {
            FanarChatModel model = new FanarChatModel(client, ChatModel.FANAR);
            assertThatThrownBy(() -> model.call(prompt()))
                    .isInstanceOfSatisfying(FanarRateLimitException.class,
                            ex -> assertThat(ex.retryAfter()).isEqualTo(Duration.ofHours(2)));
        }

        assertThat(server.hits()).as("no retry may be attempted").isEqualTo(1);
    }

    private FanarClient client(RetryPolicy policy) {
        return FanarClient.builder()
                .apiKey("test-key")
                .baseUrl(server.baseUri())
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(5))
                .retryPolicy(policy)
                .jsonCodec(new Jackson3FanarJsonCodec())
                .build();
    }

    private static Prompt prompt() {
        return new Prompt(List.of(new UserMessage("hello")));
    }
}
