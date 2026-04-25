package qa.fanar.core.translations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguagePairTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (LanguagePair lp : LanguagePair.KNOWN) {
            assertEquals(lp, LanguagePair.of(lp.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        LanguagePair custom = LanguagePair.of("fr-en");
        assertEquals("fr-en", custom.wireValue());
        assertFalse(LanguagePair.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new LanguagePair(null));
        assertThrows(NullPointerException.class, () -> LanguagePair.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(2, LanguagePair.KNOWN.size());
        assertTrue(LanguagePair.KNOWN.contains(LanguagePair.EN_AR));
        assertTrue(LanguagePair.KNOWN.contains(LanguagePair.AR_EN));
    }
}
