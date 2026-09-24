package qa.fanar.core.sadiq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReportChunkTest {

    private static final DeepResearchReport REPORT =
            new DeepResearchReport(null, null, "Title", null, null, null, null, null, null);

    @Test
    void holdsFields() {
        ReportChunk c = new ReportChunk("chatcmpl-a46c47", 1789988223L, "Fanar-Sadiq-2", REPORT);
        assertEquals("chatcmpl-a46c47", c.id());
        assertEquals(1789988223L, c.created());
        assertEquals("Fanar-Sadiq-2", c.model());
        assertEquals("Title", c.report().title());
    }

    @Test
    void modelMayBeNull() {
        assertNull(new ReportChunk("c_1", 0L, null, REPORT).model());
    }

    @Test
    void rejectsNullIdAndReport() {
        assertThrows(NullPointerException.class, () -> new ReportChunk(null, 0L, "m", REPORT));
        assertThrows(NullPointerException.class, () -> new ReportChunk("c_1", 0L, "m", null));
    }
}
