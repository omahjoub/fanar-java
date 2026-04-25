package qa.fanar.core.moderations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SafetyFilterRequestTest {

    @Test
    void holdsAllFields() {
        SafetyFilterRequest r = new SafetyFilterRequest(
                ModerationModel.FANAR_GUARD_2, "what is the weather?", "the weather is sunny");
        assertEquals(ModerationModel.FANAR_GUARD_2, r.model());
        assertEquals("what is the weather?", r.prompt());
        assertEquals("the weather is sunny", r.response());
    }

    @Test
    void ofIsEquivalentToCanonicalConstructor() {
        SafetyFilterRequest a = new SafetyFilterRequest(ModerationModel.FANAR_GUARD_2, "p", "r");
        SafetyFilterRequest b = SafetyFilterRequest.of(ModerationModel.FANAR_GUARD_2, "p", "r");
        assertEquals(a, b);
    }

    @Test
    void rejectsNullModel() {
        assertThrows(NullPointerException.class,
                () -> new SafetyFilterRequest(null, "p", "r"));
    }

    @Test
    void rejectsNullPrompt() {
        assertThrows(NullPointerException.class,
                () -> new SafetyFilterRequest(ModerationModel.FANAR_GUARD_2, null, "r"));
    }

    @Test
    void rejectsNullResponse() {
        assertThrows(NullPointerException.class,
                () -> new SafetyFilterRequest(ModerationModel.FANAR_GUARD_2, "p", null));
    }
}
