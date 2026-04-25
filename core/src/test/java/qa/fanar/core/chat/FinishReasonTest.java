package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FinishReasonTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (FinishReason fr : FinishReason.KNOWN) {
            assertEquals(fr, FinishReason.of(fr.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        FinishReason custom = FinishReason.of("partial");
        assertEquals("partial", custom.wireValue());
        assertFalse(FinishReason.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new FinishReason(null));
        assertThrows(NullPointerException.class, () -> FinishReason.of(null));
    }

    @Test
    void knownContainsAllConstants() {
        assertEquals(5, FinishReason.KNOWN.size());
    }
}
