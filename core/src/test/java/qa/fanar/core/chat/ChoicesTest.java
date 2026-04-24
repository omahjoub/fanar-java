package qa.fanar.core.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChoicesTest {

    // --- ChoiceToken

    @Test
    void choiceTokenHoldsFields() {
        ChoiceToken c = new ChoiceToken(1, "stop", "delta");
        assertEquals(1, c.index());
        assertEquals("stop", c.finishReason());
        assertEquals("delta", c.content());
    }

    @Test
    void choiceTokenRejectsNullContent() {
        assertThrows(NullPointerException.class, () -> new ChoiceToken(0, null, null));
    }

    @Test
    void choiceTokenAllowsNullFinishReason() {
        ChoiceToken c = new ChoiceToken(0, null, "x");
        assertNull(c.finishReason());
    }

    // --- ChoiceToolCall

    @Test
    void choiceToolCallHoldsFields() {
        ToolCallData tc = new ToolCallData(0, "tc_1", null,
                new FunctionData("search", "{}"));
        ChoiceToolCall c = new ChoiceToolCall(0, null, List.of(tc));
        assertEquals(1, c.toolCalls().size());
    }

    @Test
    void choiceToolCallRejectsNullToolCalls() {
        assertThrows(NullPointerException.class, () -> new ChoiceToolCall(0, null, null));
    }

    @Test
    void choiceToolCallToolCallsDefensivelyCopiedAndUnmodifiable() {
        ToolCallData tc = new ToolCallData(0, "tc_1", "function",
                new FunctionData("f", "{}"));
        List<ToolCallData> src = new ArrayList<>();
        src.add(tc);
        ChoiceToolCall c = new ChoiceToolCall(0, null, src);
        src.add(tc);
        assertEquals(1, c.toolCalls().size());
        assertThrows(UnsupportedOperationException.class, () -> c.toolCalls().add(tc));
    }

    // --- ChoiceToolResult

    @Test
    void choiceToolResultHoldsFields() {
        ToolResultData tr = new ToolResultData("tc_1", "search", Map.of(), "r", null, false);
        ChoiceToolResult c = new ChoiceToolResult(0, null, tr);
        assertEquals(tr, c.toolResult());
    }

    @Test
    void choiceToolResultRejectsNullToolResult() {
        assertThrows(NullPointerException.class, () -> new ChoiceToolResult(0, null, null));
    }

    // --- ChoiceFinal

    @Test
    void choiceFinalHoldsFields() {
        Reference ref = new Reference(0, 1, "quran", "x");
        ChoiceFinal c = new ChoiceFinal(0, "stop", List.of(ref));
        assertEquals("stop", c.finishReason());
        assertEquals(1, c.references().size());
    }

    @Test
    void choiceFinalDefaultsFinishReasonToStop() {
        ChoiceFinal c = new ChoiceFinal(0, null, null);
        assertEquals("stop", c.finishReason());
    }

    @Test
    void choiceFinalNullReferencesBecomeEmpty() {
        ChoiceFinal c = new ChoiceFinal(0, "stop", null);
        assertTrue(c.references().isEmpty());
    }

    @Test
    void choiceFinalReferencesDefensivelyCopiedAndUnmodifiable() {
        Reference ref = new Reference(0, 1, "quran", "x");
        List<Reference> src = new ArrayList<>();
        src.add(ref);
        ChoiceFinal c = new ChoiceFinal(0, "stop", src);
        src.add(ref);
        assertEquals(1, c.references().size());
        assertThrows(UnsupportedOperationException.class, () -> c.references().add(ref));
    }

    // --- ChoiceError

    @Test
    void choiceErrorHoldsFields() {
        ChoiceError c = new ChoiceError(0, "error", "boom");
        assertEquals("error", c.finishReason());
        assertEquals("boom", c.content());
    }

    @Test
    void choiceErrorDefaultsFinishReasonToError() {
        ChoiceError c = new ChoiceError(0, null, "boom");
        assertEquals("error", c.finishReason());
    }

    @Test
    void choiceErrorRejectsNullContent() {
        assertThrows(NullPointerException.class, () -> new ChoiceError(0, null, null));
    }
}
