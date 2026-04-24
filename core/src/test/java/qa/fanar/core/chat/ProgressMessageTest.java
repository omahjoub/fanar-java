package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProgressMessageTest {

    @Test
    void holdsBothLanguages() {
        ProgressMessage m = new ProgressMessage("searching corpus", "البحث في المصادر");
        assertEquals("searching corpus", m.en());
        assertEquals("البحث في المصادر", m.ar());
    }

    @Test
    void rejectsNullEn() {
        assertThrows(NullPointerException.class, () -> new ProgressMessage(null, "ar"));
    }

    @Test
    void rejectsNullAr() {
        assertThrows(NullPointerException.class, () -> new ProgressMessage("en", null));
    }
}
