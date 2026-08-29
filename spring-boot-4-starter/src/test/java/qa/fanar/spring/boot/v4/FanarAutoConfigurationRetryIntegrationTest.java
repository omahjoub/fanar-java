package qa.fanar.spring.boot.v4;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import qa.fanar.core.FanarClient;
import qa.fanar.core.FanarOverloadedException;
import qa.fanar.core.FanarRateLimitException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The starter's retry knobs proved through the context it builds: {@code fanar.retry.*} →
 * {@code RetryPolicy} bean → {@code FanarClient} bean → interceptor chain → real JDK transport →
 * a scripted local server (ADR-020). {@code FanarAutoConfigurationTest} proves the beans exist and
 * carry the right values; only a call through the wired client proves the values act.
 */
@Tag("integration")
class FanarAutoConfigurationRetryIntegrationTest {

    private static final String OK = """
            {"id":"resp-1","model":"Fanar","created":1700000000,
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"hello back"}}]}
            """;

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FanarAutoConfiguration.class))
                .withPropertyValues(
                        "fanar.api-key=test-key",
                        "fanar.base-url=" + server.baseUri(),
                        "fanar.connect-timeout=5s",
                        "fanar.request-timeout=5s",
                        "fanar.retry.initial-backoff=1ms",
                        "fanar.retry.max-delay=1s");
    }

    @Test
    void maxAttemptsOneMeansNoRetry() {
        server.enqueue(Reply.of(503, "busy"));

        runner().withPropertyValues("fanar.retry.max-attempts=1").run(ctx -> {
            FanarClient client = ctx.getBean(FanarClient.class);
            assertThatThrownBy(() -> client.chat().send(ping())).isInstanceOf(FanarOverloadedException.class);
        });

        assertThat(server.hits()).isEqualTo(1);
    }

    @Test
    void maxAttemptsThreeRetriesATransientFailure() {
        server.enqueue(Reply.of(503, "busy"), Reply.json(200, OK));

        runner().withPropertyValues("fanar.retry.max-attempts=3").run(ctx ->
                assertThat(ctx.getBean(FanarClient.class).chat().send(ping()).id()).isEqualTo("resp-1"));

        assertThat(server.hits()).as("the 503 was retried once").isEqualTo(2);
    }

    @Test
    void maxDelayIsTheRetryAfterCeiling() {
        // fanar.retry.max-delay=1s (ADR-025): a 2 s hint ends retrying at once, hint preserved.
        server.enqueue(Reply.of(429, "slow down", Map.of("Retry-After", "2")));

        runner().withPropertyValues("fanar.retry.max-attempts=3").run(ctx -> {
            FanarClient client = ctx.getBean(FanarClient.class);
            assertThatThrownBy(() -> client.chat().send(ping()))
                    .isInstanceOfSatisfying(FanarRateLimitException.class,
                            ex -> assertThat(ex.retryAfter()).isEqualTo(Duration.ofSeconds(2)));
        });

        assertThat(server.hits()).as("no retry may be attempted").isEqualTo(1);
    }

    @Test
    void userRetryPolicyBeanDrivesTheClient() {
        server.enqueue(Reply.of(503, "busy"));

        runner().withPropertyValues("fanar.retry.max-attempts=3")
                .withUserConfiguration(DisabledRetryConfig.class)
                .run(ctx -> {
                    FanarClient client = ctx.getBean(FanarClient.class);
                    assertThatThrownBy(() -> client.chat().send(ping())).isInstanceOf(FanarOverloadedException.class);
                });

        assertThat(server.hits()).as("RetryPolicy.disabled() still maps the error but never retries").isEqualTo(1);
    }

    @Configuration(proxyBeanMethods = false)
    static class DisabledRetryConfig {
        @Bean
        RetryPolicy fanarRetryPolicy() {
            return RetryPolicy.disabled();
        }
    }

    private static ChatRequest ping() {
        return ChatRequest.builder().model(ChatModel.FANAR).addMessage(UserMessage.of("ping")).build();
    }
}
