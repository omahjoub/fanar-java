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

    @Test
    void injectsSeveralMembersFirstInOrder() {
        assertEquals("{\"stream\":false,\"background\":true,\"model\":\"Fanar-Sadiq-2\"}",
                new String(StreamFlag.inject("{\"model\":\"Fanar-Sadiq-2\"}".getBytes(StandardCharsets.UTF_8),
                        "\"stream\":false,\"background\":true"), StandardCharsets.UTF_8));
        assertEquals("{\"stream\":true,\"background\":false}",
                new String(StreamFlag.inject("{}".getBytes(StandardCharsets.UTF_8),
                        "\"stream\":true,\"background\":false"), StandardCharsets.UTF_8));
    }

    @Test
    void severalMembersStillRequireAnObjectBody() {
        assertThrows(FanarTransportException.class,
                () -> StreamFlag.inject("[]".getBytes(StandardCharsets.UTF_8), "\"background\":true"));
    }

    private static String inject(String json) {
        return new String(
                StreamFlag.inject(json.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }
}
