package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageDetailTest {

    @ParameterizedTest
    @EnumSource(ImageDetail.class)
    void wireValueRoundtrips(ImageDetail detail) {
        assertEquals(detail, ImageDetail.fromWireValue(detail.wireValue()));
    }

    @Test
    void fromWireValueThrowsOnUnknown() {
        assertThrows(IllegalArgumentException.class, () -> ImageDetail.fromWireValue("unknown"));
    }

    @Test
    void fromWireValueThrowsOnNull() {
        assertThrows(NullPointerException.class, () -> ImageDetail.fromWireValue(null));
    }
}
