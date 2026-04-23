package qa.fanar.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ErrorCodeTest {

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void wireValueRoundtrips(ErrorCode code) {
        assertEquals(code, ErrorCode.fromWireValue(code.wireValue()));
    }

    @Test
    void fromWireValueThrowsOnUnknown() {
        assertThrows(IllegalArgumentException.class, () -> ErrorCode.fromWireValue("unknown_code"));
    }

    @Test
    void fromWireValueThrowsOnNull() {
        assertThrows(NullPointerException.class, () -> ErrorCode.fromWireValue(null));
    }

    @Test
    void notFoundPreservesSpecExactWireValue() {
        // Fanar's OpenAPI spec uses capitalized "Not found" with a space — not the more typical
        // "not_found". Regression guard against an accidental rename during refactors.
        assertEquals("Not found", ErrorCode.NOT_FOUND.wireValue());
    }
}
