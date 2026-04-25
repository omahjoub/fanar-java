package qa.fanar.core.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AvailableModelTest {

    @Test
    void holdsAllFields() {
        AvailableModel m = new AvailableModel("Fanar", "model", 1_700_000_000L, "fanar");
        assertEquals("Fanar", m.id());
        assertEquals("model", m.object());
        assertEquals(1_700_000_000L, m.created());
        assertEquals("fanar", m.ownedBy());
    }

    @Test
    void rejectsNullId() {
        assertThrows(NullPointerException.class,
                () -> new AvailableModel(null, "model", 0L, "fanar"));
    }

    @Test
    void rejectsNullObject() {
        assertThrows(NullPointerException.class,
                () -> new AvailableModel("Fanar", null, 0L, "fanar"));
    }

    @Test
    void rejectsNullOwnedBy() {
        assertThrows(NullPointerException.class,
                () -> new AvailableModel("Fanar", "model", 0L, null));
    }
}
