package qa.fanar.core.translations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TranslationRequestTest {

    @Test
    void holdsAllFields() {
        TranslationRequest r = new TranslationRequest(
                TranslationModel.FANAR_SHAHEEN_MT_1, "hello", LanguagePair.EN_AR,
                TranslationPreprocessing.PRESERVE_HTML);
        assertEquals(TranslationModel.FANAR_SHAHEEN_MT_1, r.model());
        assertEquals("hello", r.text());
        assertEquals(LanguagePair.EN_AR, r.langPair());
        assertEquals(TranslationPreprocessing.PRESERVE_HTML, r.preprocessing());
    }

    @Test
    void ofUsesDefaultPreprocessingNull() {
        TranslationRequest r = TranslationRequest.of(
                TranslationModel.FANAR_SHAHEEN_MT_1, "hello", LanguagePair.EN_AR);
        assertNull(r.preprocessing(),
                "of(...) should leave preprocessing null so the server applies its default");
    }

    @Test
    void rejectsNullModel() {
        assertThrows(NullPointerException.class,
                () -> new TranslationRequest(null, "t", LanguagePair.EN_AR, null));
    }

    @Test
    void rejectsNullText() {
        assertThrows(NullPointerException.class,
                () -> new TranslationRequest(TranslationModel.FANAR_SHAHEEN_MT_1, null, LanguagePair.EN_AR, null));
    }

    @Test
    void rejectsNullLangPair() {
        assertThrows(NullPointerException.class,
                () -> new TranslationRequest(TranslationModel.FANAR_SHAHEEN_MT_1, "t", null, null));
    }
}
