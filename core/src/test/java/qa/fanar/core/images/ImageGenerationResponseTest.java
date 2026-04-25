package qa.fanar.core.images;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageGenerationResponseTest {

    @Test
    void holdsAllFields() {
        ImageGenerationItem item = new ImageGenerationItem("aGVsbG8=");
        ImageGenerationResponse r = new ImageGenerationResponse(
                "req_1", 1_700_000_000L, List.of(item));
        assertEquals("req_1", r.id());
        assertEquals(1_700_000_000L, r.created());
        assertEquals(1, r.data().size());
        assertEquals(item, r.data().getFirst());
    }

    @Test
    void rejectsNullId() {
        assertThrows(NullPointerException.class,
                () -> new ImageGenerationResponse(null, 0L, List.of()));
    }

    @Test
    void rejectsNullData() {
        assertThrows(NullPointerException.class,
                () -> new ImageGenerationResponse("req", 0L, null));
    }

    @Test
    void dataListIsDefensivelyCopiedAndUnmodifiable() {
        List<ImageGenerationItem> src = new ArrayList<>();
        src.add(new ImageGenerationItem("a"));
        ImageGenerationResponse r = new ImageGenerationResponse("req", 0L, src);
        src.add(new ImageGenerationItem("b"));
        assertEquals(1, r.data().size());
        assertNotSame(src, r.data());
        assertThrows(UnsupportedOperationException.class,
                () -> r.data().add(new ImageGenerationItem("c")));
    }
}
