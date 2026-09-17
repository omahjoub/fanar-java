package qa.fanar.adk;

import java.util.List;

import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentConfig;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qa.fanar.adk.TestSupport.COMPLETION;
import static qa.fanar.adk.TestSupport.DONE;
import static qa.fanar.adk.TestSupport.DONE_SENTINEL;
import static qa.fanar.adk.TestSupport.ERROR_FRAME;
import static qa.fanar.adk.TestSupport.PLAIN_COMPLETION;
import static qa.fanar.adk.TestSupport.TRUNCATED_COMPLETION;
import static qa.fanar.adk.TestSupport.client;
import static qa.fanar.adk.TestSupport.request;
import static qa.fanar.adk.TestSupport.token;
import static qa.fanar.adk.TestSupport.user;

/**
 * {@code FanarLlm.generateContent} → {@code FanarClient} → interceptor chain → JDK transport → a
 * scripted local server, with the JSON codec discovered through {@code ServiceLoader} from the
 * adapter's own runtime-scope dependency (ADR-030). Proves the mapping in both directions and the
 * streaming protocol ADK enforces.
 */
@Tag("integration")
class FanarLlmIntegrationTest {

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    @Test
    void nonStreamingMapsTheTurnAndTheWireBody() {
        server.enqueue(Reply.json(200, COMPLETION));
        GenerateContentConfig config = GenerateContentConfig.builder()
                .temperature(0.7f)
                .maxOutputTokens(16)
                .systemInstruction(Content.fromParts(Part.fromText("Be brief")))
                .build();

        try (FanarClient client = client(server)) {
            LlmResponse r = new FanarLlm(client, ChatModel.FANAR_SADIQ)
                    .generateContent(request(config, user("ping")), false)
                    .blockingSingle();

            assertEquals("hello back", r.content().get().text());
            assertEquals("model", r.content().get().role().get());
            List<Part> parts = r.content().get().parts().get();
            assertEquals("lookup", parts.get(1).functionCall().get().name().get(),
                    "the pending call is handed to ADK; the executed retrieval is not");
            assertEquals(FinishReason.Known.STOP, r.finishReason().get().knownEnum());
            assertEquals(7, r.usageMetadata().get().totalTokenCount().get());
            assertEquals("Fanar-Sadiq", r.modelVersion().get(), "the model the server reports");
            assertEquals("Sahih al-Bukhari", r.groundingMetadata().get().groundingChunks().get().get(0)
                    .retrievedContext().get().title().get());
        }

        assertEquals(1, server.hits());
        String body = server.lastReceived().bodyAsString();
        assertTrue(server.lastReceived().path().endsWith("/v1/chat/completions"), server.lastReceived().path());
        assertTrue(body.contains("\"temperature\":0.7"), "0.7f goes on the wire as 0.7: " + body);
        assertTrue(body.contains("\"max_tokens\":16"), body);
        assertTrue(body.contains("\"model\":\"Fanar-Sadiq\""), body);
        assertTrue(body.contains("\"role\":\"system\"") && body.contains("Be brief"), body);
        assertTrue(body.contains("\"role\":\"user\"") && body.contains("ping"), body);
        assertFalse(body.contains("\"tools\""), "nothing tool-shaped is sent: " + body);
        assertEquals("Bearer test-key", server.lastReceived().header("Authorization"));
    }

    @Test
    void streamingEmitsPartialsThenExactlyOneFinalResponse() {
        server.enqueue(Reply.sse(token("hel") + token("lo") + DONE + DONE_SENTINEL));

        try (FanarClient client = client(server)) {
            List<LlmResponse> responses = new FanarLlm(() -> client, ChatModel.FANAR)
                    .generateContent(request(user("ping")), true)
                    .toList().blockingGet();

            assertEquals(3, responses.size(), "two partials, one final");
            assertEquals(true, responses.get(0).partial().get());
            assertEquals("hel", responses.get(0).content().get().text());
            assertEquals("lo", responses.get(1).content().get().text());
            LlmResponse last = responses.get(2);
            assertTrue(last.partial().isEmpty(), "the final response is not partial");
            assertEquals("hello", last.content().get().text());
            assertEquals("model", last.content().get().role().get());
            assertEquals(FinishReason.Known.STOP, last.finishReason().get().knownEnum());
            assertEquals(7, last.usageMetadata().get().totalTokenCount().get());
            assertEquals(1, last.groundingMetadata().get().groundingChunks().get().size());
        }

        assertEquals(1, server.hits());
        assertTrue(server.lastReceived().bodyAsString().contains("\"stream\":true"));
    }

    @Test
    void aStreamWithoutADoneChunkStillFinalises() {
        server.enqueue(Reply.sse(token("hi")));

        try (FanarClient client = client(server)) {
            List<LlmResponse> responses = new FanarLlm(client, ChatModel.FANAR)
                    .generateContent(request(user("ping")), true)
                    .toList().blockingGet();

            assertEquals(2, responses.size());
            LlmResponse last = responses.get(1);
            assertTrue(last.partial().isEmpty());
            assertEquals("hi", last.content().get().text());
            assertTrue(last.finishReason().isEmpty(), "no finish reason was ever seen");
            assertTrue(last.errorCode().isEmpty());
        }
        assertEquals(1, server.hits());
    }

    @Test
    void anErrorFrameBecomesAnErrorCodeNextToThePartialText() {
        server.enqueue(Reply.sse(token("par") + ERROR_FRAME));

        try (FanarClient client = client(server)) {
            LlmResponse last = new FanarLlm(client, ChatModel.FANAR)
                    .generateContent(request(user("ping")), true)
                    .blockingLast();

            assertEquals("par", last.content().get().text());
            assertEquals(FinishReason.Known.OTHER, last.errorCode().get().knownEnum());
            assertEquals("boom", last.errorMessage().get());
        }
        assertEquals(1, server.hits());
    }

    @Test
    void nothingToShowBecomesAnErrorCodeNeverAMessageAlone() {
        server.enqueue(Reply.json(200, TRUNCATED_COMPLETION));

        try (FanarClient client = client(server)) {
            LlmResponse r = new FanarLlm(client, ChatModel.FANAR)
                    .generateContent(request(user("ping")), false)
                    .blockingSingle();

            assertTrue(r.content().isEmpty());
            assertEquals(FinishReason.Known.MAX_TOKENS, r.errorCode().get().knownEnum());
            assertEquals("Fanar returned no content (finish reason: length)", r.errorMessage().get());
        }
        assertEquals(1, server.hits());
    }

    @Test
    void nothingRunsBeforeAdkSubscribes() {
        try (FanarClient client = client(server)) {
            Flowable<LlmResponse> deferred = new FanarLlm(client, ChatModel.FANAR)
                    .generateContent(request(user("ping")), false);
            assertEquals(0, server.hits(), "assembling the Flowable sends nothing");

            server.enqueue(Reply.json(200, PLAIN_COMPLETION));
            assertEquals("pong", deferred.blockingSingle().content().get().text());
        }
        assertEquals(1, server.hits());
    }
}
