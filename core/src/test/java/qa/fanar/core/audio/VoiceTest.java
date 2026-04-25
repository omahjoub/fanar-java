package qa.fanar.core.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (Voice v : Voice.KNOWN) {
            assertEquals(v, Voice.of(v.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        Voice custom = Voice.of("MyCustomVoice");
        assertEquals("MyCustomVoice", custom.wireValue());
        assertFalse(Voice.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new Voice(null));
        assertThrows(NullPointerException.class, () -> Voice.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(8, Voice.KNOWN.size());
        assertTrue(Voice.KNOWN.contains(Voice.AMELIA));
        assertTrue(Voice.KNOWN.contains(Voice.EMILY));
        assertTrue(Voice.KNOWN.contains(Voice.HAMAD));
        assertTrue(Voice.KNOWN.contains(Voice.HARRY));
        assertTrue(Voice.KNOWN.contains(Voice.HUDA));
        assertTrue(Voice.KNOWN.contains(Voice.JAKE));
        assertTrue(Voice.KNOWN.contains(Voice.JASIM));
        assertTrue(Voice.KNOWN.contains(Voice.NOOR));
    }
}
