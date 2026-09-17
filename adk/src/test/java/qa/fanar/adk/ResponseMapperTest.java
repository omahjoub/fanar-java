package qa.fanar.adk;

import java.util.List;
import java.util.Map;

import com.google.adk.models.LlmResponse;
import com.google.genai.types.FinishReason;
import com.google.genai.types.Part;
import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.ChatChoice;
import qa.fanar.core.chat.ChatMessage;
import qa.fanar.core.chat.ChatResponse;
import qa.fanar.core.chat.CompletionUsage;
import qa.fanar.core.chat.ImageContent;
import qa.fanar.core.chat.Reference;
import qa.fanar.core.chat.TextContent;
import qa.fanar.core.chat.ToolCall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseMapperTest {

    private static final CompletionUsage USAGE = new CompletionUsage(2, 5, 7, null, null, null, null);
    private static final Reference REFERENCE = new Reference(0, 1, "Book", "quote");

    private static ChatResponse response(String finish, ChatMessage message, CompletionUsage usage) {
        return new ChatResponse("id", List.of(new ChatChoice(qa.fanar.core.chat.FinishReason.of(finish), 0, message,
                null, null)), 0L, "Fanar-Sadiq", usage, Map.of());
    }

    @Test
    void textUsageAndReferencesMapWhileServerSideToolCallsAreNotEmitted() {
        ChatMessage message = new ChatMessage(
                List.of(new TextContent("hello "), new ImageContent("https://x/i.png"), new TextContent("back")),
                List.of(REFERENCE),
                List.of(new ToolCall("t1", "retrieve", Map.of("q", "x"), "done", null, false),
                        new ToolCall("t2", "lookup", Map.of("q", "y"), null, null, true)));

        LlmResponse r = ResponseMapper.toLlmResponse(response("stop", message, USAGE));

        assertEquals("model", r.content().get().role().get());
        List<Part> parts = r.content().get().parts().get();
        assertEquals(1, parts.size(), "no tool was declared, so nothing is handed to ADK for dispatch");
        assertEquals("hello back", parts.get(0).text().get());
        assertEquals(FinishReason.Known.STOP, r.finishReason().get().knownEnum());
        assertTrue(r.errorCode().isEmpty());
        assertEquals(5, r.usageMetadata().get().promptTokenCount().get());
        assertEquals(2, r.usageMetadata().get().candidatesTokenCount().get());
        assertEquals(7, r.usageMetadata().get().totalTokenCount().get());
        assertEquals("Fanar-Sadiq", r.modelVersion().get());
        var chunk = r.groundingMetadata().get().groundingChunks().get().get(0).retrievedContext().get();
        assertEquals("Book", chunk.title().get());
        assertEquals("quote", chunk.text().get());
    }

    @Test
    void anEmptyStopIsContentWhileAnEmptyTruncationIsAnError() {
        ChatMessage empty = new ChatMessage(List.of(), List.of(), List.of());

        LlmResponse stopped = ResponseMapper.toLlmResponse(response("stop", empty, null));
        LlmResponse truncated = ResponseMapper.toLlmResponse(response("length", empty, null));

        assertTrue(stopped.content().get().parts().get().isEmpty(), "content present, no parts: the event is built and history drops it");
        assertTrue(stopped.errorCode().isEmpty());
        assertTrue(stopped.usageMetadata().isEmpty());
        assertTrue(stopped.groundingMetadata().isEmpty());
        assertTrue(truncated.content().isEmpty());
        assertEquals(FinishReason.Known.MAX_TOKENS, truncated.errorCode().get().knownEnum());
        assertEquals("Fanar returned no content (finish reason: length)", truncated.errorMessage().get());
    }

    @Test
    void noChoicesIsAnErrorWithoutAFinishReason() {
        LlmResponse r = ResponseMapper.toLlmResponse(new ChatResponse("id", List.of(), 0L, "Fanar", USAGE, null));

        assertTrue(r.content().isEmpty());
        assertTrue(r.finishReason().isEmpty());
        assertEquals(FinishReason.Known.OTHER, r.errorCode().get().knownEnum());
        assertEquals("Fanar returned no choices", r.errorMessage().get());
        assertEquals(7, r.usageMetadata().get().totalTokenCount().get());
    }

    @Test
    void streamErrorsKeepThePartialTextNextToTheErrorCode() {
        LlmResponse withText = ResponseMapper.outcome(List.of(Part.fromText("partial")), "error", "boom", null,
                List.of(), "Fanar");
        LlmResponse without = ResponseMapper.outcome(List.of(), null, "boom", null, List.of(), "Fanar");
        LlmResponse truncated = ResponseMapper.outcome(List.of(), "length", "boom", null, List.of(), "Fanar");
        LlmResponse stoppedAfterError = ResponseMapper.outcome(List.of(), "stop", "boom", null, List.of(), "Fanar");

        assertEquals("partial", withText.content().get().text());
        assertEquals(FinishReason.Known.OTHER, withText.errorCode().get().knownEnum());
        assertEquals("boom", withText.errorMessage().get());
        assertTrue(without.content().isEmpty());
        assertEquals(FinishReason.Known.OTHER, without.errorCode().get().knownEnum());
        assertTrue(without.finishReason().isEmpty());
        assertEquals(FinishReason.Known.MAX_TOKENS, truncated.errorCode().get().knownEnum(), "a real reason is kept");
        assertEquals(FinishReason.Known.OTHER, stoppedAfterError.errorCode().get().knownEnum(),
                "a terminal chunk after an error frame never relabels the error as STOP");
        assertTrue(stoppedAfterError.finishReason().isEmpty(), "and never reports STOP as the finish reason of a failed call");
        assertEquals(FinishReason.Known.MAX_TOKENS, truncated.finishReason().get().knownEnum(), "a real reason is still reported");
    }

    @Test
    void nothingAtAllIsAnErrorWithoutAReason() {
        LlmResponse r = ResponseMapper.outcome(List.of(), null, null, null, List.of(), "Fanar");

        assertEquals("Fanar returned no content", r.errorMessage().get());
        assertEquals(FinishReason.Known.OTHER, r.errorCode().get().knownEnum());
        assertFalse(r.content().isPresent());
    }

    @Test
    void finishReasonsMapOntoAdksVocabulary() {
        assertEquals(FinishReason.Known.STOP, ResponseMapper.finishReason("stop").knownEnum());
        assertEquals(FinishReason.Known.STOP, ResponseMapper.finishReason("tool_calls").knownEnum());
        assertEquals(FinishReason.Known.STOP, ResponseMapper.finishReason("function_call").knownEnum());
        assertEquals(FinishReason.Known.MAX_TOKENS, ResponseMapper.finishReason("length").knownEnum());
        assertEquals(FinishReason.Known.SAFETY, ResponseMapper.finishReason("content_filter").knownEnum());
        assertEquals(FinishReason.Known.OTHER, ResponseMapper.finishReason("something_new").knownEnum());
    }
}
