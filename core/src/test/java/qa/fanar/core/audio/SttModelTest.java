package qa.fanar.core.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SttModelTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (SttModel m : SttModel.KNOWN) {
            assertEquals(m, SttModel.of(m.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        SttModel custom = SttModel.of("Fanar-Aura-STT-99");
        assertEquals("Fanar-Aura-STT-99", custom.wireValue());
        assertFalse(SttModel.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new SttModel(null));
        assertThrows(NullPointerException.class, () -> SttModel.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(2, SttModel.KNOWN.size());
        assertTrue(SttModel.KNOWN.contains(SttModel.FANAR_AURA_STT_1));
        assertTrue(SttModel.KNOWN.contains(SttModel.FANAR_AURA_STT_LF_1));
    }
}
