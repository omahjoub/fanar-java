package qa.fanar.core.poems;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PoemGenerationRequestTest {

    @Test
    void holdsAllFields() {
        PoemGenerationRequest r = new PoemGenerationRequest(
                PoemModel.FANAR_DIWAN, "Write a poem about the sea");
        assertEquals(PoemModel.FANAR_DIWAN, r.model());
        assertEquals("Write a poem about the sea", r.prompt());
    }

    @Test
    void ofIsEquivalentToCanonicalConstructor() {
        PoemGenerationRequest a = new PoemGenerationRequest(PoemModel.FANAR_DIWAN, "p");
        PoemGenerationRequest b = PoemGenerationRequest.of(PoemModel.FANAR_DIWAN, "p");
        assertEquals(a, b);
    }

    @Test
    void rejectsNullModel() {
        assertThrows(NullPointerException.class,
                () -> new PoemGenerationRequest(null, "p"));
    }

    @Test
    void rejectsNullPrompt() {
        assertThrows(NullPointerException.class,
                () -> new PoemGenerationRequest(PoemModel.FANAR_DIWAN, null));
    }
}
