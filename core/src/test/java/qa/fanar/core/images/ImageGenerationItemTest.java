package qa.fanar.core.images;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageGenerationItemTest {

    @Test
    void holdsBase64Field() {
        ImageGenerationItem item = new ImageGenerationItem("aGVsbG8=");
        assertEquals("aGVsbG8=", item.b64Json());
    }

    @Test
    void rejectsNullBase64() {
        assertThrows(NullPointerException.class, () -> new ImageGenerationItem(null));
    }
}
