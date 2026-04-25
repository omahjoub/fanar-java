package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageDetailTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (ImageDetail d : ImageDetail.KNOWN) {
            assertEquals(d, ImageDetail.of(d.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        ImageDetail custom = ImageDetail.of("ultra");
        assertEquals("ultra", custom.wireValue());
        assertFalse(ImageDetail.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new ImageDetail(null));
        assertThrows(NullPointerException.class, () -> ImageDetail.of(null));
    }

    @Test
    void knownContainsAllConstants() {
        assertEquals(3, ImageDetail.KNOWN.size());
    }
}
