package qa.fanar.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentFilterTypeTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (ContentFilterType t : ContentFilterType.KNOWN) {
            assertEquals(t, ContentFilterType.of(t.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        ContentFilterType custom = ContentFilterType.of("policy_violation");
        assertEquals("policy_violation", custom.wireValue());
        assertFalse(ContentFilterType.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new ContentFilterType(null));
        assertThrows(NullPointerException.class, () -> ContentFilterType.of(null));
    }

    @Test
    void knownContainsAllConstants() {
        assertEquals(3, ContentFilterType.KNOWN.size());
    }
}
