package qa.fanar.adk;

import java.util.List;
import java.util.Map;

import com.google.adk.models.LlmResponse;
import com.google.genai.types.FinishReason;
import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.ChoiceError;
import qa.fanar.core.chat.ChoiceFinal;
import qa.fanar.core.chat.ChoiceToken;
import qa.fanar.core.chat.ChoiceToolCall;
import qa.fanar.core.chat.ChoiceToolResult;
import qa.fanar.core.chat.CompletionUsage;
import qa.fanar.core.chat.DoneChunk;
import qa.fanar.core.chat.ErrorChunk;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.ProgressMessage;
import qa.fanar.core.chat.Reference;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.chat.ToolCallChunk;
import qa.fanar.core.chat.ToolResultChunk;
import qa.fanar.core.chat.ToolResultData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamAggregatorTest {

    private static final Reference REFERENCE = new Reference(0, 1, "Book", "quote");

    private static TokenChunk token(String text, String finish) {
        return new TokenChunk("c", 0L, "Fanar", List.of(new ChoiceToken(0, finish, text)));
    }

    @Test
    void tokensBecomePartialsAndCompletionBecomesTheAggregatedTurn() {
        StreamAggregator aggregator = new StreamAggregator("Fanar");

        List<LlmResponse> first = aggregator.onEvent(token("hel", null)).toList().blockingGet();
        List<LlmResponse> empty = aggregator.onEvent(token("", null)).toList().blockingGet();
        List<LlmResponse> second = aggregator.onEvent(token("lo", null)).toList().blockingGet();
        List<LlmResponse> done = aggregator.onEvent(new DoneChunk("c", 0L, "Fanar-C-2-27B",
                List.of(new ChoiceFinal(0, "stop", List.of(REFERENCE))),
                new CompletionUsage(2, 5, 7, null, null, null, null), null)).toList().blockingGet();
        List<LlmResponse> doneAgain = aggregator.onEvent(new DoneChunk("c", 0L, "Fanar-C-2-27B",
                List.of(new ChoiceFinal(0, null, null)), null, null)).toList().blockingGet();
        LlmResponse last = aggregator.onComplete().blockingFirst();

        assertEquals("hel", first.get(0).content().get().text());
        assertEquals(true, first.get(0).partial().get());
        assertTrue(empty.isEmpty(), "an empty delta emits nothing");
        assertEquals("lo", second.get(0).content().get().text());
        assertTrue(done.isEmpty() && doneAgain.isEmpty(), "the done chunk itself emits nothing");
        assertEquals("hello", last.content().get().text());
        assertTrue(last.partial().isEmpty());
        assertEquals(FinishReason.Known.STOP, last.finishReason().get().knownEnum());
        assertEquals(1, last.groundingMetadata().get().groundingChunks().get().size(), "references survive an empty later done chunk");
        assertTrue(last.usageMetadata().isEmpty(), "the later done chunk's null usage wins, as the server's last word");
        assertEquals("Fanar-C-2-27B", last.modelVersion().get(), "the server-reported model");
    }

    @Test
    void anErrorChunkBecomesAnErrorCodeNextToThePartialText() {
        StreamAggregator aggregator = new StreamAggregator("Fanar");
        aggregator.onEvent(token("par", "length")).toList().blockingGet();
        aggregator.onEvent(new ErrorChunk("c", 0L, "Fanar",
                List.of(new ChoiceError(0, null, "boom"), new ChoiceError(1, "error", "again")))).toList().blockingGet();

        LlmResponse last = aggregator.onComplete().blockingFirst();

        assertEquals("par", last.content().get().text());
        assertEquals(FinishReason.Known.OTHER, last.errorCode().get().knownEnum());
        assertEquals("boom\nagain", last.errorMessage().get());
    }

    @Test
    void toolChunksAndProgressAreNotEmittedButCarryTheFinishReason() {
        StreamAggregator aggregator = new StreamAggregator("Fanar");
        ToolResultData result = new ToolResultData("t1", "retrieve", Map.of(), "r", null, false);

        assertTrue(aggregator.onEvent(new ToolCallChunk("c", 0L, "Fanar",
                List.of(new ChoiceToolCall(0, null, List.of())))).toList().blockingGet().isEmpty());
        assertTrue(aggregator.onEvent(new ToolResultChunk("c", 0L, "Fanar",
                List.of(new ChoiceToolResult(0, "tool_calls", result)))).toList().blockingGet().isEmpty());
        assertTrue(aggregator.onEvent(new ProgressChunk("c", 0L, "Fanar",
                new ProgressMessage("Searching", "بحث"))).toList().blockingGet().isEmpty());
        LlmResponse last = aggregator.onComplete().blockingFirst();

        assertEquals(FinishReason.Known.STOP, last.finishReason().get().knownEnum(), "tool_calls maps to STOP");
        assertEquals("", last.content().get().text(), "stopped with nothing streamed still carries content");
    }

    @Test
    void anEmptyStreamReportsTheRequestedModelAndNoContent() {
        LlmResponse last = new StreamAggregator("Fanar-S-1-7B").onComplete().blockingFirst();

        assertEquals("Fanar-S-1-7B", last.modelVersion().get());
        assertEquals(FinishReason.Known.OTHER, last.errorCode().get().knownEnum());
        assertEquals("Fanar returned no content", last.errorMessage().get());
    }
}
