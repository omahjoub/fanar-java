package qa.fanar.core.moderations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModerationModelTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (ModerationModel m : ModerationModel.KNOWN) {
            assertEquals(m, ModerationModel.of(m.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        ModerationModel custom = ModerationModel.of("Fanar-Guard-99");
        assertEquals("Fanar-Guard-99", custom.wireValue());
        assertFalse(ModerationModel.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new ModerationModel(null));
        assertThrows(NullPointerException.class, () -> ModerationModel.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(1, ModerationModel.KNOWN.size());
        assertTrue(ModerationModel.KNOWN.contains(ModerationModel.FANAR_GUARD_2));
    }
}
