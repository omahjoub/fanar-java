package qa.fanar.core.sadiq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncompleteReasonTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (IncompleteReason r : IncompleteReason.KNOWN) {
            assertEquals(r, IncompleteReason.of(r.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        IncompleteReason custom = IncompleteReason.of("max_output_tokens");
        assertEquals("max_output_tokens", custom.wireValue());
        assertFalse(IncompleteReason.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new IncompleteReason(null));
        assertThrows(NullPointerException.class, () -> IncompleteReason.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(1, IncompleteReason.KNOWN.size());
        assertTrue(IncompleteReason.KNOWN.contains(IncompleteReason.CONTENT_FILTER));
        assertEquals("content_filter", IncompleteReason.CONTENT_FILTER.wireValue());
    }
}
