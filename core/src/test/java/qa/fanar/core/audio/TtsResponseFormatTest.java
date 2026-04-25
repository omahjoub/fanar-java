package qa.fanar.core.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsResponseFormatTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (TtsResponseFormat f : TtsResponseFormat.KNOWN) {
            assertEquals(f, TtsResponseFormat.of(f.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        TtsResponseFormat custom = TtsResponseFormat.of("flac");
        assertEquals("flac", custom.wireValue());
        assertFalse(TtsResponseFormat.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new TtsResponseFormat(null));
        assertThrows(NullPointerException.class, () -> TtsResponseFormat.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(2, TtsResponseFormat.KNOWN.size());
        assertTrue(TtsResponseFormat.KNOWN.contains(TtsResponseFormat.MP3));
        assertTrue(TtsResponseFormat.KNOWN.contains(TtsResponseFormat.WAV));
    }
}
