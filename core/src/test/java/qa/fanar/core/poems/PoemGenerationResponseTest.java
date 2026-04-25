package qa.fanar.core.poems;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PoemGenerationResponseTest {

    @Test
    void holdsAllFields() {
        PoemGenerationResponse r = new PoemGenerationResponse("req_1", "البحر يهدر بأمواجه");
        assertEquals("req_1", r.id());
        assertEquals("البحر يهدر بأمواجه", r.poem());
    }

    @Test
    void rejectsNullId() {
        assertThrows(NullPointerException.class, () -> new PoemGenerationResponse(null, "p"));
    }

    @Test
    void rejectsNullPoem() {
        assertThrows(NullPointerException.class, () -> new PoemGenerationResponse("req", null));
    }
}
