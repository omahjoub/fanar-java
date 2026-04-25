package qa.fanar.core.audio;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoiceResponseTest {

    @Test
    void holdsList() {
        VoiceResponse r = new VoiceResponse(List.of("alice", "bob"));
        assertEquals(2, r.voices().size());
        assertEquals("alice", r.voices().getFirst());
    }

    @Test
    void rejectsNullList() {
        assertThrows(NullPointerException.class, () -> new VoiceResponse(null));
    }

    @Test
    void listIsDefensivelyCopiedAndUnmodifiable() {
        List<String> src = new ArrayList<>();
        src.add("alice");
        VoiceResponse r = new VoiceResponse(src);
        src.add("bob");
        assertEquals(1, r.voices().size());
        assertNotSame(src, r.voices());
        assertThrows(UnsupportedOperationException.class, () -> r.voices().add("carol"));
    }
}
