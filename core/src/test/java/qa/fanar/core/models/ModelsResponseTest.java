package qa.fanar.core.models;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelsResponseTest {

    @Test
    void holdsAllFields() {
        AvailableModel a = new AvailableModel("Fanar", "model", 0L, "fanar");
        ModelsResponse r = new ModelsResponse("req_1", List.of(a));
        assertEquals("req_1", r.id());
        assertEquals(1, r.models().size());
        assertEquals(a, r.models().getFirst());
    }

    @Test
    void rejectsNullId() {
        assertThrows(NullPointerException.class, () -> new ModelsResponse(null, List.of()));
    }

    @Test
    void rejectsNullModels() {
        assertThrows(NullPointerException.class, () -> new ModelsResponse("req_1", null));
    }

    @Test
    void modelsListIsDefensivelyCopiedAndUnmodifiable() {
        List<AvailableModel> src = new ArrayList<>();
        src.add(new AvailableModel("Fanar", "model", 0L, "fanar"));
        ModelsResponse r = new ModelsResponse("req_1", src);
        src.add(new AvailableModel("Fanar-Sadiq", "model", 0L, "fanar"));
        assertEquals(1, r.models().size());
        assertNotSame(src, r.models());
        assertThrows(UnsupportedOperationException.class,
                () -> r.models().add(new AvailableModel("x", "model", 0L, "y")));
    }
}
