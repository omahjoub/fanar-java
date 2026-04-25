package qa.fanar.core.tokens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenizationResponseTest {

    @Test
    void holdsAllFields() {
        TokenizationResponse r = new TokenizationResponse("req_1", 42, 4096);
        assertEquals("req_1", r.id());
        assertEquals(42, r.tokens());
        assertEquals(4096, r.maxRequestTokens());
    }

    @Test
    void rejectsNullId() {
        assertThrows(NullPointerException.class, () -> new TokenizationResponse(null, 0, 0));
    }
}
