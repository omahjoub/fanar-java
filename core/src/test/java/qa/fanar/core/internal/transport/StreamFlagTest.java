package qa.fanar.core.internal.transport;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarTransportException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StreamFlagTest {

    @Test
    void injectsAsFirstPropertyWithComma() {
        assertEquals("{\"stream\":true,\"model\":\"Fanar\"}",
                inject("{\"model\":\"Fanar\"}"));
    }

    @Test
    void injectsIntoEmptyObjectWithoutComma() {
        assertEquals("{\"stream\":true}", inject("{}"));
    }

    @Test
    void rejectsNonObjectBody() {
        assertThrows(FanarTransportException.class, () -> inject("[1,2]"));
    }

    @Test
    void rejectsTooShortBody() {
        assertThrows(FanarTransportException.class, () -> inject(""));
        assertThrows(FanarTransportException.class, () -> inject("{"));
    }

    private static String inject(String json) {
        return new String(
                StreamFlag.inject(json.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }
}
