package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (Source s : Source.KNOWN) {
            assertEquals(s, Source.of(s.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        Source custom = Source.of("custom_corpus");
        assertEquals("custom_corpus", custom.wireValue());
        assertFalse(Source.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new Source(null));
        assertThrows(NullPointerException.class, () -> Source.of(null));
    }

    @Test
    void knownContainsAllConstants() {
        assertEquals(12, Source.KNOWN.size());
    }
}
