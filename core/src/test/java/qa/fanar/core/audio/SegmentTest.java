package qa.fanar.core.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SegmentTest {

    @Test
    void holdsAllFields() {
        Segment s = new Segment("speaker_0", 0.0, 1.5, 1.5, "hello");
        assertEquals("speaker_0", s.speaker());
        assertEquals(0.0, s.startTime());
        assertEquals(1.5, s.endTime());
        assertEquals(1.5, s.duration());
        assertEquals("hello", s.text());
    }

    @Test
    void rejectsNullSpeaker() {
        assertThrows(NullPointerException.class,
                () -> new Segment(null, 0.0, 1.0, 1.0, "hi"));
    }

    @Test
    void rejectsNullText() {
        assertThrows(NullPointerException.class,
                () -> new Segment("speaker_0", 0.0, 1.0, 1.0, null));
    }
}
