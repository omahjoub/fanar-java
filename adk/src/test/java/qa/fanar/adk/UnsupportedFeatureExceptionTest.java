package qa.fanar.adk;

import java.util.List;

import org.junit.jupiter.api.Test;

import qa.fanar.adk.UnsupportedFeature.Kind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnsupportedFeatureExceptionTest {

    @Test
    void namesEveryFeatureInOrderAndPointsAtTheOptOut() {
        UnsupportedFeature tool = new UnsupportedFeature(Kind.TOOL, "tool 'a'");
        UnsupportedFeature schema = new UnsupportedFeature(Kind.OUTPUT_SCHEMA, "an output schema");
        UnsupportedFeatureException ex = new UnsupportedFeatureException(List.of(tool, schema), UnsupportedFeaturePolicy.REJECT);

        assertEquals(List.of(tool, schema), ex.features());
        assertEquals(UnsupportedFeaturePolicy.REJECT, ex.policy());
        assertEquals(Kind.TOOL, ex.features().get(0).kind(), "a caller can branch on the kind");
        assertEquals("tool 'a'", tool.toString());
        assertTrue(ex.getMessage().startsWith("Fanar cannot honour tool 'a', an output schema"), ex.getMessage());
        assertTrue(ex.getMessage().contains("UnsupportedFeaturePolicy.IGNORE"), ex.getMessage());
        assertTrue(ex instanceof UnsupportedOperationException, "rooted in the JDK, not in FanarException");

        UnsupportedFeatureException dropped = new UnsupportedFeatureException(List.of(tool), UnsupportedFeaturePolicy.IGNORE);
        assertEquals("Fanar cannot honour tool 'a' - and nothing sendable remains once they are dropped", dropped.getMessage());
        assertEquals(UnsupportedFeaturePolicy.IGNORE, dropped.policy());
    }

    @Test
    void rejectsAnEmptyOrMissingListAndIncompleteFeatures() {
        assertThrows(IllegalArgumentException.class, () -> new UnsupportedFeatureException(List.of(), UnsupportedFeaturePolicy.REJECT));
        assertThrows(NullPointerException.class, () -> new UnsupportedFeatureException(null, UnsupportedFeaturePolicy.REJECT));
        assertThrows(NullPointerException.class, () -> new UnsupportedFeatureException(List.of(new UnsupportedFeature(Kind.PART, "x")), null));
        assertThrows(NullPointerException.class, () -> new UnsupportedFeature(null, "x"));
        assertThrows(NullPointerException.class, () -> new UnsupportedFeature(Kind.PART, null));
        assertEquals(3, Kind.values().length);
        assertEquals(Kind.PART, Kind.valueOf("PART"));
    }
}
