package qa.fanar.adk;

import java.util.List;
import java.util.Map;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarClient;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qa.fanar.adk.TestSupport.PLAIN_COMPLETION;
import static qa.fanar.adk.TestSupport.client;

/**
 * The unsupported-feature policy through a real ADK {@code Runner} (ADR-030): tools the agent
 * declares, the transfer tool ADK injects for a multi-agent tree, and an output schema are refused
 * before anything reaches the wire; {@code IGNORE} drops them; a workflow parent injects nothing.
 */
@Tag("integration")
class FanarLlmPolicyIntegrationTest {

    private static final FanarLlmOptions IGNORE = FanarLlmOptions.builder()
            .unsupportedFeatures(UnsupportedFeaturePolicy.IGNORE).build();

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    private final InMemorySessionService sessions = new InMemorySessionService();

    /** A static no-arg tool, the shape {@code FunctionTool.create(Class, String)} accepts. */
    public static Map<String, Object> lookup() {
        return Map.of("answer", 42);
    }

    private static UnsupportedFeature tool(String detail) {
        return new UnsupportedFeature(UnsupportedFeature.Kind.TOOL, detail);
    }

    private List<Event> run(BaseAgent agent, String text) {
        Runner runner = Runner.builder().agent(agent).appName("app")
                .artifactService(new InMemoryArtifactService()).sessionService(sessions).build();
        Session session = sessions.createSession("app", "user").blockingGet();
        return runner.runAsync("user", session.id(), Content.fromParts(Part.fromText(text))).toList().blockingGet();
    }

    @Test
    void aDeclaredToolIsRefusedBeforeTheWire() {
        try (FanarClient client = client(server)) {
            LlmAgent agent = LlmAgent.builder().name("fanar").model(new FanarLlm(client, ChatModel.FANAR))
                    .tools(FunctionTool.create(FanarLlmPolicyIntegrationTest.class, "lookup"))
                    .build();

            UnsupportedFeatureException ex = assertThrows(UnsupportedFeatureException.class, () -> run(agent, "hi"));
            assertEquals(List.of(tool("tool 'lookup'")), ex.features());
        }
        assertEquals(0, server.hits());
    }

    @Test
    void adksInjectedTransferToolIsRefusedBeforeTheWire() {
        try (FanarClient client = client(server)) {
            FanarLlm model = new FanarLlm(client, ChatModel.FANAR);
            LlmAgent child = LlmAgent.builder().name("child").model(model).build();
            LlmAgent root = LlmAgent.builder().name("root").model(model).subAgents(child).build();

            UnsupportedFeatureException ex = assertThrows(UnsupportedFeatureException.class, () -> run(root, "hi"));
            assertEquals(List.of(tool("tool 'transfer_to_agent'")), ex.features());
        }
        assertEquals(0, server.hits());
    }

    @Test
    void anOutputSchemaIsRefusedBeforeTheWire() {
        try (FanarClient client = client(server)) {
            LlmAgent agent = LlmAgent.builder().name("fanar").model(new FanarLlm(client, ChatModel.FANAR))
                    .outputSchema(Schema.builder().type("OBJECT")
                            .properties(Map.of("answer", Schema.builder().type("STRING").build())).build())
                    .build();

            UnsupportedFeatureException ex = assertThrows(UnsupportedFeatureException.class, () -> run(agent, "hi"));
            assertEquals(List.of(new UnsupportedFeature(UnsupportedFeature.Kind.OUTPUT_SCHEMA, "an output schema")), ex.features());
        }
        assertEquals(0, server.hits());
    }

    @Test
    void ignoreDropsTheFeaturesAndSendsTheRest() {
        server.enqueue(Reply.json(200, PLAIN_COMPLETION));

        try (FanarClient client = client(server)) {
            FanarLlm model = new FanarLlm(client, ChatModel.FANAR, IGNORE);
            LlmAgent child = LlmAgent.builder().name("child").model(model).build();
            LlmAgent root = LlmAgent.builder().name("root").model(model).subAgents(child)
                    .tools(FunctionTool.create(FanarLlmPolicyIntegrationTest.class, "lookup"))
                    .build();

            List<Event> events = run(root, "hi");
            assertEquals("pong", events.getLast().content().get().text());
            assertTrue(events.getLast().finalResponse());
        }

        assertEquals(1, server.hits());
        String body = server.lastReceived().bodyAsString();
        assertFalse(body.contains("\"tools\""), body);
        assertTrue(body.contains("transfer_to_agent"), "ADK's transfer instruction still travels as text: " + body);
    }

    @Test
    void aWorkflowParentInjectsNoTransferToolSoRejectNeedsNoOptIn() {
        server.enqueue(Reply.json(200, PLAIN_COMPLETION), Reply.json(200, PLAIN_COMPLETION));

        try (FanarClient client = client(server)) {
            FanarLlm model = new FanarLlm(client, ChatModel.FANAR);
            LlmAgent first = LlmAgent.builder().name("fanar1").model(model).build();
            LlmAgent second = LlmAgent.builder().name("fanar2").model(model).build();
            SequentialAgent flow = SequentialAgent.builder().name("flow").subAgents(first, second).build();

            List<Event> events = run(flow, "hi");
            assertEquals("fanar2", events.getLast().author());
            assertEquals("pong", events.getLast().content().get().text());
        }

        assertEquals(2, server.hits(), "one call per step, no transfer tool anywhere");
        String second = server.lastReceived().bodyAsString();
        assertFalse(second.contains("\"tools\""), second);
        int narration = second.indexOf("[fanar1] said:");
        assertTrue(narration >= 0 && second.indexOf("pong", narration) > narration,
                "ADK narrates step one's reply to step two, in wording ADK owns: " + second);
        assertEquals(2, second.split("\"role\":\"user\"", -1).length - 1,
                "the narration travels as a second user message, so Fanar sees two in a row: " + second);
    }
}
