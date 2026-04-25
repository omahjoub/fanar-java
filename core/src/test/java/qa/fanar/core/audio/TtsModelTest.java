package qa.fanar.core.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsModelTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (TtsModel m : TtsModel.KNOWN) {
            assertEquals(m, TtsModel.of(m.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        TtsModel custom = TtsModel.of("Fanar-Aura-TTS-99");
        assertEquals("Fanar-Aura-TTS-99", custom.wireValue());
        assertFalse(TtsModel.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new TtsModel(null));
        assertThrows(NullPointerException.class, () -> TtsModel.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(2, TtsModel.KNOWN.size());
        assertTrue(TtsModel.KNOWN.contains(TtsModel.FANAR_AURA_TTS_2));
        assertTrue(TtsModel.KNOWN.contains(TtsModel.FANAR_SADIQ_TTS_1));
    }
}
