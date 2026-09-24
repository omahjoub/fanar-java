package qa.fanar.core.sadiq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepResearchDepthTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (DeepResearchDepth d : DeepResearchDepth.KNOWN) {
            assertEquals(d, DeepResearchDepth.of(d.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        DeepResearchDepth custom = DeepResearchDepth.of("exhaustive");
        assertEquals("exhaustive", custom.wireValue());
        assertFalse(DeepResearchDepth.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new DeepResearchDepth(null));
        assertThrows(NullPointerException.class, () -> DeepResearchDepth.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(3, DeepResearchDepth.KNOWN.size());
        assertTrue(DeepResearchDepth.KNOWN.contains(DeepResearchDepth.QUICK));
        assertTrue(DeepResearchDepth.KNOWN.contains(DeepResearchDepth.STANDARD));
        assertTrue(DeepResearchDepth.KNOWN.contains(DeepResearchDepth.COMPREHENSIVE));
        assertEquals("quick", DeepResearchDepth.QUICK.wireValue());
        assertEquals("standard", DeepResearchDepth.STANDARD.wireValue());
        assertEquals("comprehensive", DeepResearchDepth.COMPREHENSIVE.wireValue());
    }
}
