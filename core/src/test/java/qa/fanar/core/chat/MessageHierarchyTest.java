package qa.fanar.core.chat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageHierarchyTest {

    // --- SystemMessage

    @Test
    void systemMessageOfFactory() {
        SystemMessage m = SystemMessage.of("you are helpful");
        assertEquals("you are helpful", m.content());
        assertEquals(null, m.name());
    }

    @Test
    void systemMessageCanonicalConstructorAcceptsName() {
        SystemMessage m = new SystemMessage("x", "admin");
        assertEquals("admin", m.name());
    }

    @Test
    void systemMessageRejectsNullContent() {
        assertThrows(NullPointerException.class, () -> new SystemMessage(null, null));
    }

    // --- UserMessage

    @Test
    void userMessageOfFactory() {
        UserMessage m = UserMessage.of("hello");
        assertEquals(1, m.content().size());
        assertEquals(new TextPart("hello"), m.content().get(0));
        assertEquals(null, m.name());
    }

    @Test
    void userMessageSupportsMultipleContentParts() {
        UserMessage m = new UserMessage(
                List.of(new TextPart("look at this"), ImagePart.of("https://example.com/a.png")),
                "alice");
        assertEquals(2, m.content().size());
        assertEquals("alice", m.name());
    }

    @Test
    void userMessageContentIsUnmodifiable() {
        UserMessage m = UserMessage.of("x");
        assertThrows(UnsupportedOperationException.class, () ->
                m.content().add(new TextPart("y")));
    }

    @Test
    void userMessageRejectsNullContent() {
        assertThrows(NullPointerException.class, () -> new UserMessage(null, null));
    }

    @Test
    void userMessageRejectsEmptyContent() {
        assertThrows(IllegalArgumentException.class, () -> new UserMessage(List.of(), null));
    }

    // --- AssistantMessage

    @Test
    void assistantMessageOfFactory() {
        AssistantMessage m = AssistantMessage.of("here you go");
        assertEquals(1, m.content().size());
        assertTrue(m.toolCalls().isEmpty());
    }

    @Test
    void assistantMessageAcceptsOnlyToolCalls() {
        ToolCall tc = new ToolCall("tc_1", "search", Map.of("q", "x"), null, null, false);
        AssistantMessage m = new AssistantMessage(null, null, List.of(tc));
        assertTrue(m.content().isEmpty());
        assertEquals(1, m.toolCalls().size());
    }

    @Test
    void assistantMessageAcceptsContentAndToolCalls() {
        ToolCall tc = new ToolCall("tc_1", "search", Map.of(), null, null, false);
        AssistantMessage m = new AssistantMessage(
                List.of(new TextPart("here's what I found")),
                "assistant",
                List.of(tc));
        assertFalse(m.content().isEmpty());
        assertFalse(m.toolCalls().isEmpty());
    }

    @Test
    void assistantMessageRejectsBothEmpty() {
        assertThrows(IllegalArgumentException.class, () ->
                new AssistantMessage(null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                new AssistantMessage(List.of(), null, List.of()));
    }

    @Test
    void assistantMessageCollectionsAreUnmodifiable() {
        AssistantMessage m = AssistantMessage.of("x");
        assertThrows(UnsupportedOperationException.class, () ->
                m.content().add(new TextPart("y")));
        assertThrows(UnsupportedOperationException.class, () ->
                m.toolCalls().add(new ToolCall("id", "n", Map.of(), null, null, false)));
    }

    // --- ThinkingMessage / ThinkingUserMessage

    @Test
    void thinkingMessageFactory() {
        ThinkingMessage m = ThinkingMessage.of("let me think...");
        assertEquals("let me think...", m.content());
    }

    @Test
    void thinkingMessageRejectsNull() {
        assertThrows(NullPointerException.class, () -> new ThinkingMessage(null));
    }

    @Test
    void thinkingUserMessageFactory() {
        ThinkingUserMessage m = ThinkingUserMessage.of("original question");
        assertEquals("original question", m.content());
    }

    @Test
    void thinkingUserMessageRejectsNull() {
        assertThrows(NullPointerException.class, () -> new ThinkingUserMessage(null));
    }

    // --- sealed hierarchy is exhaustive

    @Test
    void messageHierarchyIsExhaustive() {
        Message m = UserMessage.of("x");
        String role = switch (m) {
            case SystemMessage s        -> "system";
            case UserMessage u          -> "user";
            case AssistantMessage a     -> "assistant";
            case ThinkingMessage t      -> "thinking";
            case ThinkingUserMessage tu -> "thinking_user";
        };
        assertEquals("user", role);
        // The above switch has no default clause; if a new Message subtype is ever added,
        // the compiler flags this method until the new case is handled.
    }

    @Test
    void allVariantsCompilePolymorphically() {
        Message[] all = {
                SystemMessage.of("s"),
                UserMessage.of("u"),
                AssistantMessage.of("a"),
                ThinkingMessage.of("t"),
                ThinkingUserMessage.of("tu")
        };
        for (Message m : all) {
            assertNotNull(m);
        }
    }
}
