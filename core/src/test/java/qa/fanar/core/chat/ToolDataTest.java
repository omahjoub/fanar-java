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

class ToolDataTest {

    // --- FunctionData

    @Test
    void functionDataHoldsFields() {
        FunctionData f = new FunctionData("search", "{\"q\":\"x\"}");
        assertEquals("search", f.name());
        assertEquals("{\"q\":\"x\"}", f.arguments());
    }

    @Test
    void functionDataRejectsNulls() {
        assertThrows(NullPointerException.class, () -> new FunctionData(null, "{}"));
        assertThrows(NullPointerException.class, () -> new FunctionData("n", null));
    }

    // --- ToolCallData

    @Test
    void toolCallDataHoldsFields() {
        FunctionData f = new FunctionData("search", "{}");
        ToolCallData t = new ToolCallData(3, "tc_42", "function", f);
        assertEquals(3, t.index());
        assertEquals("tc_42", t.id());
        assertEquals("function", t.type());
        assertEquals(f, t.function());
    }

    @Test
    void toolCallDataDefaultsTypeToFunction() {
        FunctionData f = new FunctionData("n", "{}");
        ToolCallData t = new ToolCallData(0, "tc_1", null, f);
        assertEquals("function", t.type());
    }

    @Test
    void toolCallDataRejectsNulls() {
        FunctionData f = new FunctionData("n", "{}");
        assertThrows(NullPointerException.class, () -> new ToolCallData(0, null, "function", f));
        assertThrows(NullPointerException.class, () -> new ToolCallData(0, "id", "function", null));
    }

    // --- ToolResultData

    @Test
    void toolResultDataHoldsFields() {
        Map<String, Object> args = Map.of("q", "x");
        Map<String, Object> structured = Map.of("items", 3);
        ToolResultData t = new ToolResultData("tc_1", "search", args, "r", structured, true);
        assertEquals("tc_1", t.id());
        assertEquals("search", t.name());
        assertEquals(args, t.arguments());
        assertEquals("r", t.result());
        assertEquals(structured, t.structuredContent());
        assertTrue(t.isError());
    }

    @Test
    void toolResultDataNullArgumentsBecomeEmptyMap() {
        ToolResultData t = new ToolResultData("id", null, null, null, null, false);
        assertTrue(t.arguments().isEmpty());
        assertNull(t.name());
        assertNull(t.result());
        assertNull(t.structuredContent());
        assertFalse(t.isError());
    }

    @Test
    void toolResultDataRejectsNullId() {
        assertThrows(NullPointerException.class, () ->
                new ToolResultData(null, "n", Map.of(), null, null, false));
    }

    @Test
    void toolResultDataArgumentsDefensivelyCopiedAndUnmodifiable() {
        Map<String, Object> src = new HashMap<>();
        src.put("a", 1);
        ToolResultData t = new ToolResultData("id", null, src, null, null, false);
        src.put("b", 2);
        assertEquals(1, t.arguments().size());
        assertNotSame(src, t.arguments());
        assertThrows(UnsupportedOperationException.class, () -> t.arguments().put("c", 3));
    }

    @Test
    void toolResultDataStructuredContentDefensivelyCopiedWhenPresent() {
        Map<String, Object> src = new HashMap<>();
        src.put("a", 1);
        ToolResultData t = new ToolResultData("id", null, Map.of(), null, src, false);
        src.put("b", 2);
        assertEquals(1, t.structuredContent().size());
        assertThrows(UnsupportedOperationException.class, () ->
                t.structuredContent().put("c", 3));
    }
}
