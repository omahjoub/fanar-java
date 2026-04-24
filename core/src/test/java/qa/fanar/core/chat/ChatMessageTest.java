package qa.fanar.core.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageTest {

    @Test
    void nullInputsBecomeEmptyLists() {
        ChatMessage m = new ChatMessage(null, null, null);
        assertTrue(m.content().isEmpty());
        assertTrue(m.references().isEmpty());
        assertTrue(m.toolCalls().isEmpty());
    }

    @Test
    void acceptsAllThreePopulated() {
        ChatMessage m = new ChatMessage(
                List.of(new TextContent("hello")),
                List.of(new Reference(0, 1, "quran", "...")),
                List.of(new ToolCall("tc_1", "search", Map.of(), null, null, false)));
        assertEquals(1, m.content().size());
        assertEquals(1, m.references().size());
        assertEquals(1, m.toolCalls().size());
    }

    @Test
    void contentIsDefensivelyCopiedAndUnmodifiable() {
        List<ResponseContent> src = new ArrayList<>();
        src.add(new TextContent("a"));
        ChatMessage m = new ChatMessage(src, null, null);
        src.add(new TextContent("b"));
        assertEquals(1, m.content().size());
        assertThrows(UnsupportedOperationException.class, () ->
                m.content().add(new TextContent("c")));
    }

    @Test
    void referencesAreDefensivelyCopiedAndUnmodifiable() {
        List<Reference> src = new ArrayList<>();
        src.add(new Reference(0, 1, "quran", "x"));
        ChatMessage m = new ChatMessage(null, src, null);
        src.add(new Reference(0, 2, "sunnah", "y"));
        assertEquals(1, m.references().size());
        assertThrows(UnsupportedOperationException.class, () ->
                m.references().add(new Reference(0, 3, "tafsir", "z")));
    }

    @Test
    void toolCallsAreDefensivelyCopiedAndUnmodifiable() {
        ToolCall tc = new ToolCall("id", "n", Map.of(), null, null, false);
        List<ToolCall> src = new ArrayList<>();
        src.add(tc);
        ChatMessage m = new ChatMessage(null, null, src);
        src.add(tc);
        assertEquals(1, m.toolCalls().size());
        assertThrows(UnsupportedOperationException.class, () -> m.toolCalls().add(tc));
    }
}
