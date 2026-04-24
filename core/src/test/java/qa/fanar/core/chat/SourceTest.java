package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceTest {

    @ParameterizedTest
    @EnumSource(Source.class)
    void wireValueRoundtrips(Source source) {
        assertEquals(source, Source.fromWireValue(source.wireValue()));
    }

    @Test
    void fromWireValueThrowsOnUnknown() {
        assertThrows(IllegalArgumentException.class, () -> Source.fromWireValue("unknown_source"));
    }

    @Test
    void fromWireValueThrowsOnNull() {
        assertThrows(NullPointerException.class, () -> Source.fromWireValue(null));
    }

    @Test
    void twelveSourcesDefined() {
        assertEquals(12, Source.values().length);
    }
}
