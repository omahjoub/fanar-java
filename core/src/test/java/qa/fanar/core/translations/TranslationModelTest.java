package qa.fanar.core.translations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationModelTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (TranslationModel m : TranslationModel.KNOWN) {
            assertEquals(m, TranslationModel.of(m.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        TranslationModel custom = TranslationModel.of("Fanar-Shaheen-MT-99");
        assertEquals("Fanar-Shaheen-MT-99", custom.wireValue());
        assertFalse(TranslationModel.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new TranslationModel(null));
        assertThrows(NullPointerException.class, () -> TranslationModel.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(2, TranslationModel.KNOWN.size());
        assertTrue(TranslationModel.KNOWN.contains(TranslationModel.FANAR_SHAHEEN_MT_1));
        assertTrue(TranslationModel.KNOWN.contains(TranslationModel.FANAR_SHAHEEN_MT_2));
        assertEquals("Fanar-Shaheen-MT-1", TranslationModel.FANAR_SHAHEEN_MT_1.wireValue());
        assertEquals("Fanar-Shaheen-MT-2", TranslationModel.FANAR_SHAHEEN_MT_2.wireValue());
    }
}
