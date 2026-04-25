package qa.fanar.core.audio;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechToTextResponseTest {

    @Test
    void textVariantHoldsFields() {
        SpeechToTextResponse.Text t = new SpeechToTextResponse.Text("id-1", "hello");
        assertEquals("id-1", t.id());
        assertEquals("hello", t.text());
    }

    @Test
    void textRejectsNulls() {
        assertThrows(NullPointerException.class, () -> new SpeechToTextResponse.Text(null, "x"));
        assertThrows(NullPointerException.class, () -> new SpeechToTextResponse.Text("id", null));
    }

    @Test
    void srtVariantHoldsFields() {
        SpeechToTextResponse.Srt s = new SpeechToTextResponse.Srt("id-2", "1\n00:00:00,000 --> 00:00:01,000\nhi\n");
        assertEquals("id-2", s.id());
        assertTrue(s.srt().contains("hi"));
    }

    @Test
    void srtRejectsNulls() {
        assertThrows(NullPointerException.class, () -> new SpeechToTextResponse.Srt(null, "x"));
        assertThrows(NullPointerException.class, () -> new SpeechToTextResponse.Srt("id", null));
    }

    @Test
    void jsonVariantHoldsFields() {
        Segment seg = new Segment("speaker_0", 0.0, 1.0, 1.0, "hi");
        SpeechToTextResponse.Json j = new SpeechToTextResponse.Json("id-3", List.of(seg));
        assertEquals("id-3", j.id());
        assertEquals(1, j.segments().size());
        assertEquals(seg, j.segments().getFirst());
    }

    @Test
    void jsonRejectsNulls() {
        assertThrows(NullPointerException.class, () -> new SpeechToTextResponse.Json(null, List.of()));
        assertThrows(NullPointerException.class, () -> new SpeechToTextResponse.Json("id", null));
    }

    @Test
    void jsonSegmentsAreDefensivelyCopied() {
        List<Segment> mutable = new ArrayList<>();
        mutable.add(new Segment("s", 0.0, 1.0, 1.0, "a"));
        SpeechToTextResponse.Json j = new SpeechToTextResponse.Json("id", mutable);
        mutable.clear();
        assertEquals(1, j.segments().size());
        assertThrows(UnsupportedOperationException.class,
                () -> j.segments().add(new Segment("s", 0.0, 1.0, 1.0, "b")));
    }

    @Test
    void exhaustiveSwitchOverSealedHierarchy() {
        SpeechToTextResponse[] all = {
                new SpeechToTextResponse.Text("a", "hi"),
                new SpeechToTextResponse.Srt("b", "subs"),
                new SpeechToTextResponse.Json("c", List.of()),
        };
        for (SpeechToTextResponse r : all) {
            String label = switch (r) {
                case SpeechToTextResponse.Text t -> "text:" + t.text();
                case SpeechToTextResponse.Srt s -> "srt:" + s.srt();
                case SpeechToTextResponse.Json j -> "json:" + j.segments().size();
            };
            assertNotNull(label);
        }
    }
}
