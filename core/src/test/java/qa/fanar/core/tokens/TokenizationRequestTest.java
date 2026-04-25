package qa.fanar.core.tokens;

import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.ChatModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenizationRequestTest {

    @Test
    void holdsAllFields() {
        TokenizationRequest r = new TokenizationRequest("hello", ChatModel.FANAR_S_1_7B);
        assertEquals("hello", r.content());
        assertEquals(ChatModel.FANAR_S_1_7B, r.model());
    }

    @Test
    void ofIsEquivalentToCanonicalConstructor() {
        TokenizationRequest a = new TokenizationRequest("hello", ChatModel.FANAR_S_1_7B);
        TokenizationRequest b = TokenizationRequest.of("hello", ChatModel.FANAR_S_1_7B);
        assertEquals(a, b);
    }

    @Test
    void rejectsNullContent() {
        assertThrows(NullPointerException.class,
                () -> new TokenizationRequest(null, ChatModel.FANAR_S_1_7B));
    }

    @Test
    void rejectsNullModel() {
        assertThrows(NullPointerException.class,
                () -> new TokenizationRequest("hello", null));
    }
}
