package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatChoiceTest {

    @Test
    void holdsAllFields() {
        ChatMessage msg = new ChatMessage(null, null, null);
        ChoiceLogprobs lp = new ChoiceLogprobs(null, null);
        ChatChoice c = new ChatChoice(FinishReason.STOP, 0, msg, lp, "<end_of_turn>");
        assertEquals(FinishReason.STOP, c.finishReason());
        assertEquals(0, c.index());
        assertEquals(msg, c.message());
        assertEquals(lp, c.logprobs());
        assertEquals("<end_of_turn>", c.stopReason());
    }

    @Test
    void logprobsAndStopReasonMayBeNull() {
        ChatChoice c = new ChatChoice(FinishReason.STOP, 0, new ChatMessage(null, null, null), null, null);
        assertNull(c.logprobs());
        assertNull(c.stopReason());
    }

    @Test
    void rejectsNullFinishReason() {
        assertThrows(NullPointerException.class, () ->
                new ChatChoice(null, 0, new ChatMessage(null, null, null), null, null));
    }

    @Test
    void rejectsNullMessage() {
        assertThrows(NullPointerException.class, () ->
                new ChatChoice(FinishReason.STOP, 0, null, null, null));
    }
}
