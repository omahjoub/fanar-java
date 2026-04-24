package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatModelTest {

    @ParameterizedTest
    @EnumSource(ChatModel.class)
    void wireValueRoundtrips(ChatModel model) {
        assertEquals(model, ChatModel.fromWireValue(model.wireValue()));
    }

    @Test
    void fromWireValueThrowsOnUnknown() {
        assertThrows(IllegalArgumentException.class, () -> ChatModel.fromWireValue("NotAModel"));
    }

    @Test
    void fromWireValueThrowsOnNull() {
        assertThrows(NullPointerException.class, () -> ChatModel.fromWireValue(null));
    }

    @Test
    void thinkingModelWireValueContainsDotAsInSpec() {
        // Fanar-C-1-8.7B has a literal period in the wire value that the Java enum name can't
        // encode. Regression guard against accidental rename during refactors.
        assertEquals("Fanar-C-1-8.7B", ChatModel.FANAR_C_1_8_7B.wireValue());
    }
}
