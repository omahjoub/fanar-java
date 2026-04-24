package qa.fanar.core.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamEventTest {

    // --- TokenChunk

    @Test
    void tokenChunkHoldsFields() {
        TokenChunk t = new TokenChunk("c_1", 1L, "Fanar",
                List.of(new ChoiceToken(0, null, "hello")));
        assertEquals("c_1", t.id());
        assertEquals(1L, t.created());
        assertEquals("Fanar", t.model());
        assertEquals(1, t.choices().size());
    }

    @Test
    void tokenChunkRejectsNulls() {
        assertThrows(NullPointerException.class, () -> new TokenChunk(null, 0, "m", List.of()));
        assertThrows(NullPointerException.class, () -> new TokenChunk("id", 0, null, List.of()));
        assertThrows(NullPointerException.class, () -> new TokenChunk("id", 0, "m", null));
    }

    @Test
    void tokenChunkChoicesAreDefensivelyCopiedAndUnmodifiable() {
        List<ChoiceToken> src = new ArrayList<>();
        src.add(new ChoiceToken(0, null, "a"));
        TokenChunk t = new TokenChunk("id", 0, "m", src);
        src.add(new ChoiceToken(1, null, "b"));
        assertEquals(1, t.choices().size());
        assertNotSame(src, t.choices());
        assertThrows(UnsupportedOperationException.class, () ->
                t.choices().add(new ChoiceToken(2, null, "c")));
    }

    // --- ToolCallChunk

    @Test
    void toolCallChunkHoldsFields() {
        ToolCallData tc = new ToolCallData(0, "tc_1", "function",
                new FunctionData("search", "{\"q\":\"x\"}"));
        ToolCallChunk c = new ToolCallChunk("c_1", 1L, "Fanar-Sadiq",
                List.of(new ChoiceToolCall(0, null, List.of(tc))));
        assertEquals(1, c.choices().size());
        assertEquals(1, c.choices().getFirst().toolCalls().size());
    }

    @Test
    void toolCallChunkRejectsNulls() {
        assertThrows(NullPointerException.class, () ->
                new ToolCallChunk(null, 0, "m", List.of()));
        assertThrows(NullPointerException.class, () ->
                new ToolCallChunk("id", 0, null, List.of()));
        assertThrows(NullPointerException.class, () ->
                new ToolCallChunk("id", 0, "m", null));
    }

    // --- ToolResultChunk

    @Test
    void toolResultChunkHoldsFields() {
        ToolResultData tr = new ToolResultData("tc_1", "search", Map.of("q", "x"),
                "result", null, false);
        ToolResultChunk c = new ToolResultChunk("c_1", 1L, "Fanar-Sadiq",
                List.of(new ChoiceToolResult(0, null, tr)));
        assertEquals(1, c.choices().size());
        assertEquals("result", c.choices().getFirst().toolResult().result());
    }

    @Test
    void toolResultChunkRejectsNulls() {
        assertThrows(NullPointerException.class, () ->
                new ToolResultChunk(null, 0, "m", List.of()));
        assertThrows(NullPointerException.class, () ->
                new ToolResultChunk("id", 0, null, List.of()));
        assertThrows(NullPointerException.class, () ->
                new ToolResultChunk("id", 0, "m", null));
    }

    // --- ProgressChunk

    @Test
    void progressChunkHoldsFields() {
        ProgressChunk p = new ProgressChunk("c_1", 1L, "Fanar-Sadiq",
                new ProgressMessage("searching corpus", "البحث في المصادر"));
        assertEquals("searching corpus", p.message().en());
        assertEquals("البحث في المصادر", p.message().ar());
    }

    @Test
    void progressChunkRejectsNulls() {
        ProgressMessage m = new ProgressMessage("a", "b");
        assertThrows(NullPointerException.class, () -> new ProgressChunk(null, 0, "m", m));
        assertThrows(NullPointerException.class, () -> new ProgressChunk("id", 0, null, m));
        assertThrows(NullPointerException.class, () -> new ProgressChunk("id", 0, "m", null));
    }

    // --- DoneChunk

    @Test
    void doneChunkHoldsFields() {
        DoneChunk d = new DoneChunk("c_1", 1L, "Fanar", List.of(), null, null);
        assertTrue(d.choices().isEmpty());
        assertTrue(d.metadata().isEmpty());
    }

    @Test
    void doneChunkWithUsageAndReferences() {
        CompletionUsage usage = new CompletionUsage(10, 5, 15, null, null);
        Reference ref = new Reference(0, 1, "quran", "...");
        DoneChunk d = new DoneChunk("c_1", 1L, "Fanar-Sadiq",
                List.of(new ChoiceFinal(0, "stop", List.of(ref))),
                usage, Map.of("trace", "abc"));
        assertEquals(usage, d.usage());
        assertEquals(1, d.choices().getFirst().references().size());
        assertEquals("abc", d.metadata().get("trace"));
    }

    @Test
    void doneChunkRejectsNulls() {
        assertThrows(NullPointerException.class, () -> new DoneChunk(null, 0, "m", List.of(), null, null));
        assertThrows(NullPointerException.class, () -> new DoneChunk("id", 0, null, List.of(), null, null));
        assertThrows(NullPointerException.class, () -> new DoneChunk("id", 0, "m", null, null, null));
    }

    @Test
    void doneChunkMetadataDefensivelyCopiedAndUnmodifiable() {
        java.util.Map<String, Object> src = new java.util.HashMap<>();
        src.put("a", 1);
        DoneChunk d = new DoneChunk("id", 0, "m", List.of(), null, src);
        src.put("b", 2);
        assertEquals(1, d.metadata().size());
        assertThrows(UnsupportedOperationException.class, () -> d.metadata().put("c", 3));
    }

    // --- ErrorChunk

    @Test
    void errorChunkHoldsFields() {
        ErrorChunk e = new ErrorChunk("c_1", 1L, "Fanar",
                List.of(new ChoiceError(0, null, "something broke")));
        assertEquals(1, e.choices().size());
        assertEquals("something broke", e.choices().getFirst().content());
        assertEquals("error", e.choices().getFirst().finishReason());
    }

    @Test
    void errorChunkRejectsNulls() {
        assertThrows(NullPointerException.class, () -> new ErrorChunk(null, 0, "m", List.of()));
        assertThrows(NullPointerException.class, () -> new ErrorChunk("id", 0, null, List.of()));
        assertThrows(NullPointerException.class, () -> new ErrorChunk("id", 0, "m", null));
    }

    // --- sealed hierarchy exhaustive

    @Test
    void sealedHierarchyIsExhaustive() {
        StreamEvent event = new TokenChunk("c", 0, "m",
                List.of(new ChoiceToken(0, null, "x")));
        String kind = switch (event) {
            case TokenChunk      t -> "token";
            case ToolCallChunk   c -> "tool-call";
            case ToolResultChunk r -> "tool-result";
            case ProgressChunk   p -> "progress";
            case DoneChunk       d -> "done";
            case ErrorChunk      e -> "error";
        };
        assertEquals("token", kind);
    }

    @Test
    void commonMetadataFieldsAccessibleViaSealedInterface() {
        StreamEvent event = new TokenChunk("c_id", 42L, "Fanar",
                List.of(new ChoiceToken(0, null, "x")));
        assertEquals("c_id", event.id());
        assertEquals(42L, event.created());
        assertEquals("Fanar", event.model());
    }
}
