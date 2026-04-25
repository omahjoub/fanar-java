package qa.fanar.core.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuranReciterTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (QuranReciter r : QuranReciter.KNOWN) {
            assertEquals(r, QuranReciter.of(r.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        QuranReciter custom = QuranReciter.of("custom-reciter");
        assertEquals("custom-reciter", custom.wireValue());
        assertFalse(QuranReciter.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new QuranReciter(null));
        assertThrows(NullPointerException.class, () -> QuranReciter.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(3, QuranReciter.KNOWN.size());
        assertTrue(QuranReciter.KNOWN.contains(QuranReciter.ABDUL_BASIT));
        assertTrue(QuranReciter.KNOWN.contains(QuranReciter.MAHER_AL_MUAIQLY));
        assertTrue(QuranReciter.KNOWN.contains(QuranReciter.MAHMOUD_AL_HUSARY));
    }
}
