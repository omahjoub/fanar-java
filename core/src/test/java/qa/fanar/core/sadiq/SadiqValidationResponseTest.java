package qa.fanar.core.sadiq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SadiqValidationResponseTest {

    @Test
    void holdsAllFields() {
        SadiqValidationResponse r = new SadiqValidationResponse(
                "req_1", "<quran_start>x<quran_end> [2:255](https://quran.com/2/255)");
        assertEquals("req_1", r.id());
        assertEquals("<quran_start>x<quran_end> [2:255](https://quran.com/2/255)", r.text());
    }

    @Test
    void unverifiedQuotationsComeBackUntagged() {
        SadiqValidationResponse r = new SadiqValidationResponse("req_2", "an unverifiable quote");
        assertEquals("an unverifiable quote", r.text());
    }

    @Test
    void rejectsNullId() {
        assertThrows(NullPointerException.class,
                () -> new SadiqValidationResponse(null, "t"));
    }

    @Test
    void rejectsNullText() {
        assertThrows(NullPointerException.class,
                () -> new SadiqValidationResponse("req_1", null));
    }
}
