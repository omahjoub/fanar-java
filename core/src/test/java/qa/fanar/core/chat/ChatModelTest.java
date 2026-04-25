package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatModelTest {

    @Test
    void knownConstantsRoundtripThroughOf() {
        for (ChatModel m : ChatModel.KNOWN) {
            assertEquals(m, ChatModel.of(m.wireValue()));
        }
    }

    @Test
    void ofIsLenientOnUnknownValues() {
        ChatModel custom = ChatModel.of("Fanar-D-3-50B");
        assertEquals("Fanar-D-3-50B", custom.wireValue());
        assertFalse(ChatModel.KNOWN.contains(custom),
                "unknown wire values construct successfully but are not in KNOWN");
    }

    @Test
    void rejectsNullWireValue() {
        assertThrows(NullPointerException.class, () -> new ChatModel(null));
        assertThrows(NullPointerException.class, () -> ChatModel.of(null));
    }

    @Test
    void knownContainsAllConstants() {
        assertEquals(6, ChatModel.KNOWN.size());
        assertTrue(ChatModel.KNOWN.contains(ChatModel.FANAR));
        assertTrue(ChatModel.KNOWN.contains(ChatModel.FANAR_ORYX_IVU_2));
    }

    @Test
    void thinkingModelWireValueContainsDotAsInSpec() {
        // Fanar-C-1-8.7B has a literal period in the wire value that an enum name couldn't
        // encode. Regression guard against accidental rename during refactors.
        assertEquals("Fanar-C-1-8.7B", ChatModel.FANAR_C_1_8_7B.wireValue());
    }
}
