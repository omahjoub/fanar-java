package qa.fanar.core.chat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallTest {

    @Test
    void canonicalConstructorPopulatesAllFields() {
        ToolCall tc = new ToolCall(
                "tc_42",
                "search",
                Map.of("query", "java"),
                "result-payload",
                Map.of("items", 3),
                false);
        assertEquals("tc_42", tc.id());
        assertEquals("search", tc.name());
        assertEquals(Map.of("query", "java"), tc.arguments());
        assertEquals("result-payload", tc.result());
        assertEquals(Map.of("items", 3), tc.structuredContent());
        assertFalse(tc.isError());
    }

    @Test
    void nullResultAndStructuredContentAreAccepted() {
        ToolCall tc = new ToolCall("id", "name", Map.of(), null, null, false);
        assertNull(tc.result());
        assertNull(tc.structuredContent());
    }

    @Test
    void isErrorTrueSurfaced() {
        ToolCall tc = new ToolCall("id", "name", Map.of(), "boom", null, true);
        assertTrue(tc.isError());
    }

    @Test
    void rejectsNullId() {
        assertThrows(NullPointerException.class, () ->
                new ToolCall(null, "n", Map.of(), null, null, false));
    }

    @Test
    void rejectsNullName() {
        assertThrows(NullPointerException.class, () ->
                new ToolCall("id", null, Map.of(), null, null, false));
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () ->
                new ToolCall("id", "n", null, null, null, false));
    }

    @Test
    void argumentsAreDefensivelyCopied() {
        Map<String, Object> input = new HashMap<>();
        input.put("a", 1);
        ToolCall tc = new ToolCall("id", "n", input, null, null, false);
        input.put("b", 2);
        // Mutating the input map must not leak into the record.
        assertEquals(1, tc.arguments().size());
    }

    @Test
    void argumentsAreUnmodifiable() {
        ToolCall tc = new ToolCall("id", "n", Map.of("a", 1), null, null, false);
        assertThrows(UnsupportedOperationException.class, () ->
                tc.arguments().put("b", 2));
    }

    @Test
    void defensiveCopyReplacesInputReference() {
        Map<String, Object> input = new HashMap<>();
        input.put("a", 1);
        ToolCall tc = new ToolCall("id", "n", input, null, null, false);
        assertNotSame(input, tc.arguments());
    }
}
