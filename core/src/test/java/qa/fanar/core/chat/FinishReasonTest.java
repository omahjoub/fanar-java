package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FinishReasonTest {

    @ParameterizedTest
    @EnumSource(FinishReason.class)
    void wireValueRoundtrips(FinishReason fr) {
        assertEquals(fr, FinishReason.fromWireValue(fr.wireValue()));
    }

    @Test
    void fromWireValueThrowsOnUnknown() {
        assertThrows(IllegalArgumentException.class, () -> FinishReason.fromWireValue("unknown"));
    }

    @Test
    void fromWireValueThrowsOnNull() {
        assertThrows(NullPointerException.class, () -> FinishReason.fromWireValue(null));
    }

    @Test
    void fiveReasonsDefined() {
        assertEquals(5, FinishReason.values().length);
    }
}
