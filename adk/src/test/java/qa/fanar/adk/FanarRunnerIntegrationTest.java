package qa.fanar.adk;

import java.util.List;
import java.util.Optional;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarClient;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qa.fanar.adk.TestSupport.DONE;
import static qa.fanar.adk.TestSupport.DONE_SENTINEL;
import static qa.fanar.adk.TestSupport.PLAIN_COMPLETION;
import static qa.fanar.adk.TestSupport.client;
import static qa.fanar.adk.TestSupport.token;

/**
 * The streaming protocol through a real ADK {@code Runner} and session (ADR-030): a streamed turn
 * costs exactly one model call because the aggregated final response ends the loop, the session
 * persists one non-partial event with role {@code model}, and the next turn re-sends it as history.
 */
@Tag("integration")
class FanarRunnerIntegrationTest {

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    @Test
    void aStreamedTurnCostsOneCallPersistsOneEventAndBecomesHistory() {
        server.enqueue(Reply.sse(token("hel") + token("lo") + DONE + DONE_SENTINEL));
        server.enqueue(Reply.json(200, PLAIN_COMPLETION));
        InMemorySessionService sessions = new InMemorySessionService();

        try (FanarClient client = client(server)) {
            LlmAgent agent = LlmAgent.builder().name("fanar").model(new FanarLlm(client, ChatModel.FANAR))
                    .instruction("Reply briefly").build();
            Runner runner = Runner.builder().agent(agent).appName("app")
                    .artifactService(new InMemoryArtifactService()).sessionService(sessions).build();
            Session session = sessions.createSession("app", "user").blockingGet();

            List<Event> events = runner.runAsync("user", session.id(), Content.fromParts(Part.fromText("ping")),
                            RunConfig.builder().setStreamingMode(StreamingMode.SSE).build())
                    .toList().blockingGet();

            assertEquals(2, events.stream().filter(e -> e.partial().orElse(false)).count(), "two partial events");
            Event last = events.getLast();
            assertTrue(last.finalResponse(), "the aggregated response ends the loop");
            assertEquals("hello", last.content().get().text());
            assertEquals("model", last.content().get().role().get());
            assertEquals(1, server.hits(), "exactly one model call for the turn");
            String first = server.lastReceived().bodyAsString();
            assertTrue(first.contains("\"role\":\"system\"") && first.contains("Reply briefly"),
                    "the agent instruction travels as the first system message on the very first turn: " + first);
            assertTrue(first.indexOf("\"role\":\"system\"") < first.indexOf("\"role\":\"user\""), first);

            Session stored = sessions.getSession("app", "user", session.id(), Optional.empty()).blockingGet();
            List<Event> persisted = stored.events().stream().filter(e -> "fanar".equals(e.author())).toList();
            assertEquals(1, persisted.size(), "partials are never persisted; the final event is");
            assertEquals("hello", persisted.get(0).content().get().text());

            Event reply = runner.runAsync("user", session.id(), Content.fromParts(Part.fromText("again")))
                    .toList().blockingGet().getLast();
            assertEquals("pong", reply.content().get().text());
        }

        assertEquals(2, server.hits());
        String body = server.lastReceived().bodyAsString();
        assertTrue(body.contains("\"role\":\"system\"") && body.contains("Reply briefly"), body);
        assertTrue(body.contains("\"role\":\"assistant\"") && body.contains("hello"), "history re-sent: " + body);
        assertTrue(body.contains("again"), body);
    }
}
