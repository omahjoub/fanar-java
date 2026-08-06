package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MadhabTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (Madhab m : Madhab.KNOWN) {
            assertEquals(m, Madhab.of(m.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        Madhab custom = Madhab.of("zahiri");
        assertEquals("zahiri", custom.wireValue());
        assertFalse(Madhab.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new Madhab(null));
        assertThrows(NullPointerException.class, () -> Madhab.of(null));
    }

    @Test
    void knownContainsAllConstants() {
        assertEquals(5, Madhab.KNOWN.size());
    }
}
