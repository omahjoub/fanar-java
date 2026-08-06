package qa.fanar.core.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoiceTypeTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (VoiceType t : VoiceType.KNOWN) {
            assertEquals(t, VoiceType.of(t.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        VoiceType custom = VoiceType.of("organizational");
        assertEquals("organizational", custom.wireValue());
        assertFalse(VoiceType.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new VoiceType(null));
        assertThrows(NullPointerException.class, () -> VoiceType.of(null));
    }

    @Test
    void knownContainsAllConstants() {
        assertEquals(2, VoiceType.KNOWN.size());
    }
}
