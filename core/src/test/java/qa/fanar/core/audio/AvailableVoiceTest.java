package qa.fanar.core.audio;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvailableVoiceTest {

    @Test
    void holdsAllFields() {
        AvailableVoice v = new AvailableVoice(
                "Amelia", "أميليا", "Female", "British", List.of("en"), VoiceType.PUBLIC, false);
        assertEquals("Amelia", v.name());
        assertEquals("أميليا", v.nameAr());
        assertEquals("Female", v.gender());
        assertEquals("British", v.accent());
        assertEquals(List.of("en"), v.languages());
        assertEquals(VoiceType.PUBLIC, v.type());
        assertEquals(false, v.emotion());
    }

    @Test
    void descriptiveFieldsAreNullable() {
        // Personalized voices in the spec example carry only name / languages / type / emotion.
        AvailableVoice v = new AvailableVoice(
                "MyVoice", null, null, null, List.of(), VoiceType.PERSONAL, false);
        assertNull(v.nameAr());
        assertNull(v.gender());
        assertNull(v.accent());
        assertTrue(v.languages().isEmpty());
    }

    @Test
    void nullLanguagesStaysNull() {
        AvailableVoice v = new AvailableVoice(
                "MyVoice", null, null, null, null, VoiceType.PERSONAL, false);
        assertNull(v.languages());
    }

    @Test
    void rejectsNullName() {
        assertThrows(NullPointerException.class, () -> new AvailableVoice(
                null, null, null, null, List.of(), VoiceType.PUBLIC, false));
    }

    @Test
    void rejectsNullType() {
        assertThrows(NullPointerException.class, () -> new AvailableVoice(
                "Amelia", null, null, null, List.of(), null, false));
    }

    @Test
    void languagesIsDefensivelyCopiedAndUnmodifiable() {
        List<String> src = new ArrayList<>(List.of("en"));
        AvailableVoice v = new AvailableVoice(
                "Amelia", null, null, null, src, VoiceType.PUBLIC, true);
        src.add("ar");
        assertEquals(1, v.languages().size());
        assertThrows(UnsupportedOperationException.class, () -> v.languages().add("ar"));
        assertEquals(true, v.emotion());
    }
}
