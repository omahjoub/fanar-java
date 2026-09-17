package qa.fanar.adk;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
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

import qa.fanar.core.FanarClient;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static qa.fanar.adk.TestSupport.PLAIN_COMPLETION;
import static qa.fanar.adk.TestSupport.client;

/**
 * The leaf-specialist recipe (ADR-030): a root on a model that can call tools transfers to a Fanar
 * leaf that disallows transfer to parent and peers. ADK injects no tool into the leaf's request,
 * the leaf answers under the default REJECT policy, and the next user turn goes back to the root.
 * The root is a stand-in {@code BaseLlm} because Fanar itself cannot emit {@code transfer_to_agent}.
 */
@Tag("integration")
class FanarLeafIntegrationTest {

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    /** Transfers to the leaf on its first call and answers in text afterwards. */
    static final class TransferringRoot extends BaseLlm {

        final AtomicInteger calls = new AtomicInteger();

        TransferringRoot() {
            super("stand-in-root");
        }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
            Part part = calls.incrementAndGet() == 1
                    ? Part.fromFunctionCall("transfer_to_agent", Map.of("agent_name", "fanar"))
                    : Part.fromText("root again");
            return Flowable.just(LlmResponse.builder()
                    .content(Content.builder().role("model").parts(List.of(part)).build())
                    .build());
        }

        @Override
        public BaseLlmConnection connect(LlmRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void aFanarLeafAnswersOnceAndTheNextTurnReturnsToTheRoot() {
        server.enqueue(Reply.json(200, PLAIN_COMPLETION));
        TransferringRoot rootModel = new TransferringRoot();
        InMemorySessionService sessions = new InMemorySessionService();

        try (FanarClient client = client(server)) {
            LlmAgent leaf = LlmAgent.builder().name("fanar").description("Arabic specialist")
                    .model(new FanarLlm(client, ChatModel.FANAR))
                    .disallowTransferToParent(true)
                    .disallowTransferToPeers(true)
                    .build();
            LlmAgent root = LlmAgent.builder().name("root").model(rootModel).subAgents(leaf).build();
            Runner runner = Runner.builder().agent(root).appName("app")
                    .artifactService(new InMemoryArtifactService()).sessionService(sessions).build();
            Session session = sessions.createSession("app", "user").blockingGet();

            List<Event> turnOne = runner.runAsync("user", session.id(), Content.fromParts(Part.fromText("ping")))
                    .toList().blockingGet();
            assertEquals("fanar", turnOne.getLast().author(), "the leaf answered after the transfer");
            assertEquals("pong", turnOne.getLast().content().get().text());
            assertEquals(1, server.hits());
            assertFalse(server.lastReceived().bodyAsString().contains("\"tools\""),
                    "no transfer tool was injected into the leaf's request");

            List<Event> turnTwo = runner.runAsync("user", session.id(), Content.fromParts(Part.fromText("again")))
                    .toList().blockingGet();
            assertEquals("root", turnTwo.getLast().author(), "the runner routed the next turn back to the root");
            assertEquals("root again", turnTwo.getLast().content().get().text());
            assertEquals(2, rootModel.calls.get());
        }

        assertEquals(1, server.hits(), "Fanar was called exactly once across both turns");
    }
}
