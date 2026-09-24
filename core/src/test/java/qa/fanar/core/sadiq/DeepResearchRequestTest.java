package qa.fanar.core.sadiq;

import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.ChatModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeepResearchRequestTest {

    @Test
    void holdsAllFields() {
        DeepResearchRequest r = new DeepResearchRequest(
                ChatModel.FANAR_SADIQ_2, "seeking knowledge", DeepResearchDepth.QUICK, true);
        assertEquals(ChatModel.FANAR_SADIQ_2, r.model());
        assertEquals("seeking knowledge", r.input());
        assertEquals(DeepResearchDepth.QUICK, r.depth());
        assertEquals(Boolean.TRUE, r.webSearch());
    }

    @Test
    void ofLeavesDepthAndWebSearchToTheServer() {
        DeepResearchRequest r = DeepResearchRequest.of(ChatModel.FANAR_SADIQ_2, "topic");
        assertEquals(new DeepResearchRequest(ChatModel.FANAR_SADIQ_2, "topic", null, null), r);
        assertNull(r.depth());
        assertNull(r.webSearch());
    }

    @Test
    void rejectsNullModel() {
        assertThrows(NullPointerException.class, () -> new DeepResearchRequest(null, "topic", null, null));
        assertThrows(NullPointerException.class, () -> DeepResearchRequest.of(null, "topic"));
    }

    @Test
    void rejectsNullInput() {
        assertThrows(NullPointerException.class,
                () -> new DeepResearchRequest(ChatModel.FANAR_SADIQ_2, null, null, null));
        assertThrows(NullPointerException.class, () -> DeepResearchRequest.of(ChatModel.FANAR_SADIQ_2, null));
    }
}
