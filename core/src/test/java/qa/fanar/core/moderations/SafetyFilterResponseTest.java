package qa.fanar.core.moderations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SafetyFilterResponseTest {

    @Test
    void holdsAllFields() {
        SafetyFilterResponse r = new SafetyFilterResponse(0.95, 0.88, "req_1");
        assertEquals(0.95, r.safety());
        assertEquals(0.88, r.culturalAwareness());
        assertEquals("req_1", r.id());
    }

    @Test
    void idMayBeNull() {
        SafetyFilterResponse r = new SafetyFilterResponse(0.95, 0.88, null);
        assertNull(r.id());
    }
}
