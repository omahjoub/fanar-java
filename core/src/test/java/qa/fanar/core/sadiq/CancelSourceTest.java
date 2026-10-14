package qa.fanar.core.sadiq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CancelSourceTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (CancelSource s : CancelSource.KNOWN) {
            assertEquals(s, CancelSource.of(s.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        CancelSource custom = CancelSource.of("system");
        assertEquals("system", custom.wireValue());
        assertFalse(CancelSource.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new CancelSource(null));
        assertThrows(NullPointerException.class, () -> CancelSource.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(2, CancelSource.KNOWN.size());
        assertEquals("owner", CancelSource.OWNER.wireValue());
        assertEquals("admin", CancelSource.ADMIN.wireValue());
    }
}
