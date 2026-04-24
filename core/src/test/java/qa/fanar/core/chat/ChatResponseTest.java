package qa.fanar.core.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatResponseTest {

    private static ChatChoice choice() {
        return new ChatChoice(FinishReason.STOP, 0, new ChatMessage(null, null, null), null);
    }

    @Test
    void holdsAllFields() {
        ChatResponse r = new ChatResponse(
                "chat_abc",
                List.of(choice()),
                1_700_000_000L,
                "Fanar-S-1-7B",
                new CompletionUsage(10, 5, 15, null, null),
                Map.of("trace", "xyz"));
        assertEquals("chat_abc", r.id());
        assertEquals(1, r.choices().size());
        assertEquals(1_700_000_000L, r.created());
        assertEquals("Fanar-S-1-7B", r.model());
        assertEquals(15, r.usage().totalTokens());
        assertEquals("xyz", r.metadata().get("trace"));
    }

    @Test
    void usageMayBeNull() {
        ChatResponse r = new ChatResponse("id", List.of(choice()), 0, "m", null, null);
        assertNull(r.usage());
    }

    @Test
    void nullMetadataBecomesEmptyMap() {
        ChatResponse r = new ChatResponse("id", List.of(choice()), 0, "m", null, null);
        assertTrue(r.metadata().isEmpty());
    }

    @Test
    void rejectsNullId() {
        assertThrows(NullPointerException.class, () ->
                new ChatResponse(null, List.of(choice()), 0, "m", null, null));
    }

    @Test
    void rejectsNullChoices() {
        assertThrows(NullPointerException.class, () ->
                new ChatResponse("id", null, 0, "m", null, null));
    }

    @Test
    void rejectsNullModel() {
        assertThrows(NullPointerException.class, () ->
                new ChatResponse("id", List.of(choice()), 0, null, null, null));
    }

    @Test
    void choicesAreDefensivelyCopiedAndUnmodifiable() {
        List<ChatChoice> src = new ArrayList<>();
        src.add(choice());
        ChatResponse r = new ChatResponse("id", src, 0, "m", null, null);
        src.add(choice());
        assertEquals(1, r.choices().size());
        assertThrows(UnsupportedOperationException.class, () -> r.choices().add(choice()));
        assertNotSame(src, r.choices());
    }

    @Test
    void metadataIsDefensivelyCopiedAndUnmodifiable() {
        Map<String, Object> src = new HashMap<>();
        src.put("a", 1);
        ChatResponse r = new ChatResponse("id", List.of(choice()), 0, "m", null, src);
        src.put("b", 2);
        assertEquals(1, r.metadata().size());
        assertThrows(UnsupportedOperationException.class, () -> r.metadata().put("c", 3));
    }
}
