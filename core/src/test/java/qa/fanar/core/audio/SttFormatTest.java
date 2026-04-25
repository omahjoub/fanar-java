package qa.fanar.core.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SttFormatTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (SttFormat f : SttFormat.KNOWN) {
            assertEquals(f, SttFormat.of(f.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        SttFormat custom = SttFormat.of("vtt");
        assertEquals("vtt", custom.wireValue());
        assertFalse(SttFormat.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new SttFormat(null));
        assertThrows(NullPointerException.class, () -> SttFormat.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(3, SttFormat.KNOWN.size());
        assertTrue(SttFormat.KNOWN.contains(SttFormat.TEXT));
        assertTrue(SttFormat.KNOWN.contains(SttFormat.SRT));
        assertTrue(SttFormat.KNOWN.contains(SttFormat.JSON));
    }

    @Test
    void wireValuesMatchSpec() {
        assertEquals("text", SttFormat.TEXT.wireValue());
        assertEquals("srt", SttFormat.SRT.wireValue());
        assertEquals("json", SttFormat.JSON.wireValue());
    }
}
