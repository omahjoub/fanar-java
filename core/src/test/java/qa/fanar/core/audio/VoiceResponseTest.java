package qa.fanar.core.audio;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoiceResponseTest {

    private static AvailableVoice voice(String name, VoiceType type) {
        return new AvailableVoice(name, null, null, null, List.of(), type, false);
    }

    @Test
    void holdsList() {
        VoiceResponse r = new VoiceResponse(List.of(
                voice("Amelia", VoiceType.PUBLIC), voice("MyVoice", VoiceType.PERSONAL)));
        assertEquals(2, r.voices().size());
        assertEquals("Amelia", r.voices().getFirst().name());
    }

    @Test
    void rejectsNullList() {
        assertThrows(NullPointerException.class, () -> new VoiceResponse(null));
    }

    @Test
    void listIsDefensivelyCopiedAndUnmodifiable() {
        List<AvailableVoice> src = new ArrayList<>();
        src.add(voice("Amelia", VoiceType.PUBLIC));
        VoiceResponse r = new VoiceResponse(src);
        src.add(voice("Hamad", VoiceType.PUBLIC));
        assertEquals(1, r.voices().size());
        assertNotSame(src, r.voices());
        assertThrows(UnsupportedOperationException.class, () ->
                r.voices().add(voice("Noor", VoiceType.PUBLIC)));
    }
}
