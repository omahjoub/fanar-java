package qa.fanar.core.sadiq;

import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.ChatModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SadiqValidationRequestTest {

    @Test
    void holdsAllFields() {
        SadiqValidationRequest r = new SadiqValidationRequest(
                ChatModel.FANAR_SADIQ_2, "a quoted verse");
        assertEquals(ChatModel.FANAR_SADIQ_2, r.model());
        assertEquals("a quoted verse", r.text());
    }

    @Test
    void ofIsEquivalentToCanonicalConstructor() {
        SadiqValidationRequest a = new SadiqValidationRequest(ChatModel.FANAR_SADIQ_2, "t");
        SadiqValidationRequest b = SadiqValidationRequest.of(ChatModel.FANAR_SADIQ_2, "t");
        assertEquals(a, b);
    }

    @Test
    void rejectsNullModel() {
        assertThrows(NullPointerException.class,
                () -> new SadiqValidationRequest(null, "t"));
    }

    @Test
    void rejectsNullText() {
        assertThrows(NullPointerException.class,
                () -> new SadiqValidationRequest(ChatModel.FANAR_SADIQ_2, null));
    }
}
