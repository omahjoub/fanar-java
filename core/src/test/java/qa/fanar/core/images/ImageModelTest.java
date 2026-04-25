package qa.fanar.core.images;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageModelTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (ImageModel m : ImageModel.KNOWN) {
            assertEquals(m, ImageModel.of(m.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        ImageModel custom = ImageModel.of("Fanar-Oryx-IG-99");
        assertEquals("Fanar-Oryx-IG-99", custom.wireValue());
        assertFalse(ImageModel.KNOWN.contains(custom));
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new ImageModel(null));
        assertThrows(NullPointerException.class, () -> ImageModel.of(null));
    }

    @Test
    void knownContainsBundledConstants() {
        assertEquals(1, ImageModel.KNOWN.size());
        assertTrue(ImageModel.KNOWN.contains(ImageModel.FANAR_ORYX_IG_2));
    }
}
