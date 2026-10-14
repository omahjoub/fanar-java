package qa.fanar.core.sadiq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IncompleteDetailsTest {

    @Test
    void holdsTheReason() {
        assertEquals(IncompleteReason.CONTENT_FILTER, new IncompleteDetails(IncompleteReason.CONTENT_FILTER).reason());
    }

    @Test
    void rejectsNullReason() {
        assertThrows(NullPointerException.class, () -> new IncompleteDetails(null));
    }
}
