package qa.fanar.core.internal.sse;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SseFrameAssemblerTest {

    @Test
    void singleDataLineEmitsOnBlankLine() {
        SseFrameAssembler a = new SseFrameAssembler();
        assertNull(a.accept("data: {\"id\":\"c_1\"}"));
        SseFrame frame = a.accept("");
        assertEquals("{\"id\":\"c_1\"}", frame.data());
    }

    @Test
    void dataWithoutSpaceAfterColonIsAccepted() {
        SseFrameAssembler a = new SseFrameAssembler();
        a.accept("data:hello");
        SseFrame frame = a.accept("");
        assertEquals("hello", frame.data());
    }

    @Test
    void multipleDataLinesJoinWithNewlines() {
        SseFrameAssembler a = new SseFrameAssembler();
        a.accept("data: line1");
        a.accept("data: line2");
        a.accept("data: line3");
        SseFrame frame = a.accept("");
        assertEquals("line1\nline2\nline3", frame.data());
    }

    @Test
    void blankLineWithNoDataIsNoOp() {
        SseFrameAssembler a = new SseFrameAssembler();
        assertNull(a.accept(""));
        assertNull(a.accept(""));
    }

    @Test
    void commentLinesAreIgnored() {
        SseFrameAssembler a = new SseFrameAssembler();
        assertNull(a.accept(": heartbeat"));
        assertNull(a.accept(":"));
        a.accept("data: payload");
        SseFrame frame = a.accept("");
        assertEquals("payload", frame.data());
    }

    @Test
    void unknownFieldsAreIgnored() {
        SseFrameAssembler a = new SseFrameAssembler();
        assertNull(a.accept("event: token"));
        assertNull(a.accept("id: 42"));
        assertNull(a.accept("retry: 1000"));
        assertNull(a.accept("custom-field: hello"));
        assertNull(a.accept("no-colon-line"));
        a.accept("data: body");
        SseFrame frame = a.accept("");
        assertEquals("body", frame.data());
    }

    @Test
    void crlfLineEndingsAreNormalized() {
        SseFrameAssembler a = new SseFrameAssembler();
        a.accept("data: body\r");
        SseFrame frame = a.accept("\r");
        assertEquals("body", frame.data());
    }

    @Test
    void multipleFramesInSequence() {
        SseFrameAssembler a = new SseFrameAssembler();
        List<String> frames = new ArrayList<>();

        for (String line : List.of(
                "data: one", "",
                "data: two", "",
                ": keepalive",
                "data: three", ""
        )) {
            SseFrame f = a.accept(line);
            if (f != null) frames.add(f.data());
        }

        assertEquals(List.of("one", "two", "three"), frames);
    }

    @Test
    void colonOnlyFieldNameMeansEmptyValue() {
        // Per SSE spec, "data:" (no value, no space) produces an empty payload line.
        SseFrameAssembler a = new SseFrameAssembler();
        a.accept("data:");
        a.accept("data: continuation");
        SseFrame frame = a.accept("");
        assertEquals("\ncontinuation", frame.data());
    }
}
