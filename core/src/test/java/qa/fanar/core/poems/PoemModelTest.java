package qa.fanar.core.poems;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoemModelTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (PoemModel m : PoemModel.KNOWN) {
            assertEquals(m, PoemModel.of(m.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        PoemModel custom = PoemModel.of("Fanar-Diwan-2");
        assertEquals("Fanar-Diwan-2", custom.wireValue());
        assertFalse(PoemModel.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new PoemModel(null));
        assertThrows(NullPointerException.class, () -> PoemModel.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(1, PoemModel.KNOWN.size());
        assertTrue(PoemModel.KNOWN.contains(PoemModel.FANAR_DIWAN));
    }
}
