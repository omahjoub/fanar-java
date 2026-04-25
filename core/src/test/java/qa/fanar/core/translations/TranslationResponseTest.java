package qa.fanar.core.translations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TranslationResponseTest {

    @Test
    void holdsAllFields() {
        TranslationResponse r = new TranslationResponse("req_1", "مرحبا");
        assertEquals("req_1", r.id());
        assertEquals("مرحبا", r.text());
    }

    @Test
    void rejectsNullId() {
        assertThrows(NullPointerException.class, () -> new TranslationResponse(null, "t"));
    }

    @Test
    void rejectsNullText() {
        assertThrows(NullPointerException.class, () -> new TranslationResponse("req", null));
    }
}
