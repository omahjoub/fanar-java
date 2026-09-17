package qa.fanar.adk;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarAuthenticationException;
import qa.fanar.core.FanarClient;
import qa.fanar.core.FanarRateLimitException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static qa.fanar.adk.TestSupport.FAST_RETRY;
import static qa.fanar.adk.TestSupport.PLAIN_COMPLETION;
import static qa.fanar.adk.TestSupport.client;
import static qa.fanar.adk.TestSupport.request;
import static qa.fanar.adk.TestSupport.user;

/**
 * The adapter on top of a retry-enabled client (ADR-030): the client's {@code RetryPolicy} is the
 * only retry there is, and Fanar errors reach ADK unwrapped, through the {@code Flowable} and on
 * into {@code onModelErrorCallback}. Mirrors the Spring AI adapter's
 * {@code FanarChatModelRetryIntegrationTest}.
 */
@Tag("integration")
class FanarLlmErrorIntegrationTest {

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    @Test
    void aTransientFailureIsRetriedByTheClientNotTheAdapter() {
        server.enqueue(Reply.of(503, "busy"), Reply.json(200, PLAIN_COMPLETION));

        try (FanarClient client = client(server, FAST_RETRY)) {
            LlmResponse r = new FanarLlm(client, ChatModel.FANAR)
                    .generateContent(request(user("ping")), false)
                    .blockingSingle();
            assertEquals("pong", r.content().get().text());
        }

        assertEquals(2, server.hits(), "the 503 was retried once");
    }

    @Test
    void aRateLimitAboveTheCeilingSurfacesUnwrapped() {
        server.enqueue(Reply.of(429, "come back later", Map.of("Retry-After", "7200")));

        try (FanarClient client = client(server, RetryPolicy.defaults())) {
            Flowable<LlmResponse> call = new FanarLlm(client, ChatModel.FANAR)
                    .generateContent(request(user("ping")), false);

            FanarRateLimitException ex = assertThrows(FanarRateLimitException.class, call::blockingSingle);
            assertEquals(Duration.ofHours(2), ex.retryAfter());
            assertEquals(429, ex.httpStatus());
            assertNotNull(ex.code());
        }

        assertEquals(1, server.hits(), "no retry may be attempted");
    }

    @Test
    void aStreamingHandshakeFailureSurfacesUnwrapped() {
        server.enqueue(Reply.of(401, "bad key"));

        try (FanarClient client = client(server)) {
            Flowable<LlmResponse> call = new FanarLlm(client, ChatModel.FANAR)
                    .generateContent(request(user("ping")), true);

            FanarAuthenticationException ex = assertThrows(FanarAuthenticationException.class, call::blockingLast);
            assertEquals(401, ex.httpStatus());
        }

        assertEquals(1, server.hits());
    }

    @Test
    void aClientThatCannotBeBuiltSurfacesAsAModelErrorWithItsMessage() {
        AtomicReference<Exception> seen = new AtomicReference<>();
        InMemorySessionService sessions = new InMemorySessionService();
        // What FanarClient.Builder.build() throws with no key: the text a consumer would otherwise
        // lose to ADK's agent loader when the client is built in a static initialiser.
        String message = "No Fanar API key configured. Call FanarClient.Builder.apiKey(...) or set the FANAR_API_KEY environment variable.";

        LlmAgent agent = LlmAgent.builder()
                .name("fanar")
                .model(new FanarLlm(() -> { throw new IllegalStateException(message); }, ChatModel.FANAR))
                .onModelErrorCallbackSync((context, llmRequest, error) -> {
                    seen.set(error);
                    return Optional.empty();
                })
                .build();
        Runner runner = Runner.builder().agent(agent).appName("app")
                .artifactService(new InMemoryArtifactService()).sessionService(sessions).build();
        Session session = sessions.createSession("app", "user").blockingGet();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> runner
                .runAsync("user", session.id(), Content.fromParts(Part.fromText("ping")))
                .toList().blockingGet());
        assertEquals(message, ex.getMessage(), "the builder's own message survives");
        assertSame(ex, seen.get(), "and reaches onModelErrorCallback first");
        assertEquals(0, server.hits());
    }

    @Test
    void aModelErrorReachesAdksCallbackAndThenTheCaller() {
        server.enqueue(Reply.of(429, "come back later", Map.of("Retry-After", "7200")));
        AtomicReference<Exception> seen = new AtomicReference<>();
        InMemorySessionService sessions = new InMemorySessionService();

        try (FanarClient client = client(server, RetryPolicy.defaults())) {
            LlmAgent agent = LlmAgent.builder()
                    .name("fanar")
                    .model(new FanarLlm(client, ChatModel.FANAR))
                    .onModelErrorCallbackSync((context, llmRequest, error) -> {
                        seen.set(error);
                        return Optional.empty();
                    })
                    .build();
            Runner runner = Runner.builder().agent(agent).appName("app")
                    .artifactService(new InMemoryArtifactService()).sessionService(sessions).build();
            Session session = sessions.createSession("app", "user").blockingGet();

            FanarRateLimitException ex = assertThrows(FanarRateLimitException.class, () -> runner
                    .runAsync("user", session.id(), Content.fromParts(Part.fromText("ping")))
                    .toList().blockingGet());
            assertSame(ex, seen.get(), "the callback saw the same unwrapped exception");
        }

        assertEquals(1, server.hits());
    }
}
