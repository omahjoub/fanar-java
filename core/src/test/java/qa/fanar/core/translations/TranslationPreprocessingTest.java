package qa.fanar.core.translations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationPreprocessingTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (TranslationPreprocessing p : TranslationPreprocessing.KNOWN) {
            assertEquals(p, TranslationPreprocessing.of(p.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        TranslationPreprocessing custom = TranslationPreprocessing.of("future_mode");
        assertEquals("future_mode", custom.wireValue());
        assertFalse(TranslationPreprocessing.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new TranslationPreprocessing(null));
        assertThrows(NullPointerException.class, () -> TranslationPreprocessing.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(4, TranslationPreprocessing.KNOWN.size());
        assertTrue(TranslationPreprocessing.KNOWN.contains(TranslationPreprocessing.DEFAULT));
        assertTrue(TranslationPreprocessing.KNOWN.contains(TranslationPreprocessing.PRESERVE_HTML));
        assertTrue(TranslationPreprocessing.KNOWN.contains(TranslationPreprocessing.PRESERVE_WHITESPACE));
        assertTrue(TranslationPreprocessing.KNOWN.contains(TranslationPreprocessing.PRESERVE_WHITESPACE_AND_HTML));
    }
}
