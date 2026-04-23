package qa.fanar.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentFilterTypeTest {

    @ParameterizedTest
    @EnumSource(ContentFilterType.class)
    void wireValueRoundtrips(ContentFilterType type) {
        assertEquals(type, ContentFilterType.fromWireValue(type.wireValue()));
    }

    @Test
    void fromWireValueThrowsOnUnknown() {
        assertThrows(IllegalArgumentException.class, () -> ContentFilterType.fromWireValue("unknown"));
    }

    @Test
    void fromWireValueThrowsOnNull() {
        assertThrows(NullPointerException.class, () -> ContentFilterType.fromWireValue(null));
    }
}
