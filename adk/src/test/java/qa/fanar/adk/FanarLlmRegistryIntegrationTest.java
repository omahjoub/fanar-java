package qa.fanar.adk;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRegistry;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarClient;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qa.fanar.adk.TestSupport.PLAIN_COMPLETION;
import static qa.fanar.adk.TestSupport.client;
import static qa.fanar.adk.TestSupport.request;
import static qa.fanar.adk.TestSupport.user;

/**
 * Opt-in registration with ADK's JVM-global {@code LlmRegistry} (ADR-030). The registry has no
 * public eviction, so this is the only test class that registers, and it does so in one method:
 * nothing is registered by loading the class, a registered name resolves lazily on the first step
 * to one shared instance, and the client is built on the first request.
 */
@Tag("integration")
class FanarLlmRegistryIntegrationTest {

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    @Test
    void registrationIsExplicitLazyAndShared() {
        assertThrows(IllegalArgumentException.class, () -> LlmRegistry.getLlm("fanar/Fanar-Probe"),
                "loading FanarLlm registers nothing");
        server.enqueue(Reply.json(200, PLAIN_COMPLETION), Reply.json(200, PLAIN_COMPLETION));
        AtomicInteger builds = new AtomicInteger();
        InMemorySessionService sessions = new InMemorySessionService();

        try (FanarClient client = client(server)) {
            FanarLlm.register(() -> {
                builds.incrementAndGet();
                return client;
            }, FanarLlmOptions.builder().persona("scholar").build());

            BaseLlm resolved = LlmRegistry.getLlm("fanar/Fanar-Probe");
            FanarLlm probe = assertInstanceOf(FanarLlm.class, resolved);
            assertEquals("Fanar-Probe", probe.chatModel().wireValue(), "the prefix is stripped");
            assertEquals("scholar", probe.options().persona());
            assertSame(resolved, LlmRegistry.getLlm("fanar/Fanar-Probe"), "one instance per name");
            assertEquals(0, builds.get(), "the client is built on the first request, not at registration");

            LlmAgent agent = LlmAgent.builder().name("byname").model("fanar/Fanar").build();
            Runner runner = Runner.builder().agent(agent).appName("app")
                    .artifactService(new InMemoryArtifactService()).sessionService(sessions).build();
            Session session = sessions.createSession("app", "user").blockingGet();
            List<Event> events = runner.runAsync("user", session.id(), Content.fromParts(Part.fromText("ping")))
                    .toList().blockingGet();
            assertEquals("pong", events.getLast().content().get().text());
            assertEquals(1, builds.get());
            String body = server.lastReceived().bodyAsString();
            assertTrue(body.contains("\"model\":\"Fanar\"") && body.contains("\"persona\":\"scholar\""), body);

            FanarLlm.register(client);
            FanarLlm plain = assertInstanceOf(FanarLlm.class, LlmRegistry.getLlm("fanar/Fanar-Other"));
            assertEquals(UnsupportedFeaturePolicy.REJECT, plain.options().unsupportedFeatures());
            assertEquals("pong", plain.generateContent(request(user("ping")), false).blockingSingle()
                    .content().get().text());
        }

        assertEquals(2, server.hits());
    }
}
