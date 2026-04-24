package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReferenceTest {

    @Test
    void referenceHoldsAllFields() {
        Reference r = new Reference(42, 3, "quran", "Say: He is Allah, the One.");
        assertEquals(42, r.index());
        assertEquals(3, r.number());
        assertEquals("quran", r.source());
        assertEquals("Say: He is Allah, the One.", r.content());
    }

    @Test
    void rejectsNullSource() {
        assertThrows(NullPointerException.class, () ->
                new Reference(0, 0, null, "content"));
    }

    @Test
    void rejectsNullContent() {
        assertThrows(NullPointerException.class, () ->
                new Reference(0, 0, "quran", null));
    }

    @Test
    void customSourceStringsAreAccepted() {
        // Fanar may return extensions like "digital_seerah_*" that are outside the Source enum.
        Reference r = new Reference(0, 0, "digital_seerah_al_bukhari", "content");
        assertEquals("digital_seerah_al_bukhari", r.source());
    }
}
