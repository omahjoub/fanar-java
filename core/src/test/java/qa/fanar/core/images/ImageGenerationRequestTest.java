package qa.fanar.core.images;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageGenerationRequestTest {

    @Test
    void holdsAllFields() {
        ImageGenerationRequest r = new ImageGenerationRequest(
                ImageModel.FANAR_ORYX_IG_2, "A futuristic cityscape at sunset", false);
        assertEquals(ImageModel.FANAR_ORYX_IG_2, r.model());
        assertEquals("A futuristic cityscape at sunset", r.prompt());
        assertEquals(false, r.revise());
    }

    @Test
    void ofIsEquivalentToCanonicalConstructorWithServerDefaultRevision() {
        ImageGenerationRequest a = new ImageGenerationRequest(ImageModel.FANAR_ORYX_IG_2, "p", null);
        ImageGenerationRequest b = ImageGenerationRequest.of(ImageModel.FANAR_ORYX_IG_2, "p");
        assertEquals(a, b);
        // null → omitted on the wire → the server applies its default (revise=true).
        assertNull(b.revise());
    }

    @Test
    void rejectsNullModel() {
        assertThrows(NullPointerException.class,
                () -> new ImageGenerationRequest(null, "p", null));
    }

    @Test
    void rejectsNullPrompt() {
        assertThrows(NullPointerException.class,
                () -> new ImageGenerationRequest(ImageModel.FANAR_ORYX_IG_2, null, null));
    }
}
