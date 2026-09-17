package qa.fanar.adk;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnsupportedFeatureExceptionTest {

    @Test
    void namesEveryFeatureInOrderAndPointsAtTheOptOut() {
        UnsupportedFeatureException ex = new UnsupportedFeatureException(List.of("tool 'a'", "an output schema"));

        assertEquals(List.of("tool 'a'", "an output schema"), ex.features());
        assertTrue(ex.getMessage().startsWith("Fanar cannot honour tool 'a', an output schema"), ex.getMessage());
        assertTrue(ex.getMessage().contains("UnsupportedFeaturePolicy.IGNORE"), ex.getMessage());
        assertTrue(ex instanceof UnsupportedOperationException, "rooted in the JDK, not in FanarException");
    }

    @Test
    void rejectsAnEmptyOrMissingList() {
        assertThrows(IllegalArgumentException.class, () -> new UnsupportedFeatureException(List.of()));
        assertThrows(NullPointerException.class, () -> new UnsupportedFeatureException(null));
    }
}
