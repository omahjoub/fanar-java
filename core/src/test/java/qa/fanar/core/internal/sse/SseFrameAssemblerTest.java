package qa.fanar.core.internal.sse;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // --- the last event id (ADR-032): WHATWG's buffer, committed by the blank line

    @Test
    void anIdIsCommittedOnlyByTheBlankLineThatEndsItsBlock() {
        SseFrameAssembler a = new SseFrameAssembler();
        assertNull(a.lastEventId(), "no id before any block");
        a.accept("id: 1791099047115-0");
        a.accept("data: {}");
        assertNull(a.lastEventId(), "an event cut off before its blank line must not move the resume point");
        a.accept("");
        assertEquals("1791099047115-0", a.lastEventId());
    }

    @Test
    void anIdOnlyBlockCommitsItsIdWithoutDispatchingAFrame() {
        SseFrameAssembler a = new SseFrameAssembler();
        a.accept("id: 7");
        assertNull(a.accept(""), "a block without data dispatches nothing");
        assertEquals("7", a.lastEventId());
    }

    @Test
    void aBlockWithoutAnIdKeepsThePreviousOne() {
        SseFrameAssembler a = new SseFrameAssembler();
        a.accept("id: 1");
        a.accept("data: one");
        a.accept("");
        a.accept("data: two");
        a.accept("");
        assertEquals("1", a.lastEventId());
    }

    @Test
    void aResumedConnectionStartsFromTheIdItWasSeededWith() {
        SseFrameAssembler a = new SseFrameAssembler("1791099077711-0");
        assertEquals("1791099077711-0", a.lastEventId());
        a.accept("data: an event without an id");
        a.accept("");
        assertEquals("1791099077711-0", a.lastEventId(),
                "an id-less event on a resumed connection must not reset the resume point");
        a.accept("id: 1791099077744-0");
        a.accept("data: {}");
        a.accept("");
        assertEquals("1791099077744-0", a.lastEventId());
    }

    @Test
    void anIdContainingNulIsIgnored() {
        SseFrameAssembler a = new SseFrameAssembler("5");
        a.accept("id: bad\u0000id");
        a.accept("data: {}");
        a.accept("");
        assertEquals("5", a.lastEventId());
    }

    @Test
    void anEmptyIdClearsTheResumePoint() {
        SseFrameAssembler a = new SseFrameAssembler("5");
        a.accept("id:");
        a.accept("");
        assertNull(a.lastEventId(), "id: with no value clears the id");
        a.accept("id: 6");
        a.accept("");
        a.accept("id");
        a.accept("");
        assertNull(a.lastEventId(), "a bare id field clears it too");
    }

    @Test
    void anIdLineWithCrlfIsNormalized() {
        SseFrameAssembler a = new SseFrameAssembler();
        a.accept("id: 9\r");
        a.accept("\r");
        assertEquals("9", a.lastEventId());
    }

    @Test
    void progressMeansABlankLineCompletedANonEmptyBlock() {
        SseFrameAssembler a = new SseFrameAssembler();
        a.accept("");
        assertFalse(a.progressed(), "a bare blank line completes nothing");
        a.accept("data: {\"partial\"");
        assertFalse(a.progressed(), "an event cut off before its blank line is not progress");
        a.accept("");
        assertTrue(a.progressed());
    }

    @Test
    void aKeepaliveBlockIsProgress() {
        SseFrameAssembler a = new SseFrameAssembler();
        a.accept(": keepalive");
        assertFalse(a.progressed(), "not before its blank line");
        assertNull(a.accept(""));
        assertTrue(a.progressed(), "a keepalive block shows the connection is alive");
    }
}
