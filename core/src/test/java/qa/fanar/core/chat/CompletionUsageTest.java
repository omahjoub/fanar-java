package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompletionUsageTest {

    @Test
    void minimalUsage() {
        CompletionUsage u = new CompletionUsage(10, 5, 15, null, null);
        assertEquals(10, u.completionTokens());
        assertEquals(5, u.promptTokens());
        assertEquals(15, u.totalTokens());
        assertNull(u.completionTokensDetails());
        assertNull(u.promptTokensDetails());
    }

    @Test
    void usageWithDetails() {
        CompletionTokensDetails cd = new CompletionTokensDetails(null, null, 42, null);
        PromptTokensDetails pd = new PromptTokensDetails(null, 100);
        CompletionUsage u = new CompletionUsage(10, 5, 15, cd, pd);
        assertEquals(42, u.completionTokensDetails().reasoningTokens());
        assertEquals(100, u.promptTokensDetails().cachedTokens());
    }

    @Test
    void completionTokensDetailsAllFieldsOptional() {
        CompletionTokensDetails d = new CompletionTokensDetails(null, null, null, null);
        assertNull(d.acceptedPredictionTokens());
        assertNull(d.audioTokens());
        assertNull(d.reasoningTokens());
        assertNull(d.rejectedPredictionTokens());
    }

    @Test
    void completionTokensDetailsHoldsAllFields() {
        CompletionTokensDetails d = new CompletionTokensDetails(1, 2, 3, 4);
        assertEquals(1, d.acceptedPredictionTokens());
        assertEquals(2, d.audioTokens());
        assertEquals(3, d.reasoningTokens());
        assertEquals(4, d.rejectedPredictionTokens());
    }

    @Test
    void promptTokensDetailsAllFieldsOptional() {
        PromptTokensDetails d = new PromptTokensDetails(null, null);
        assertNull(d.audioTokens());
        assertNull(d.cachedTokens());
    }

    @Test
    void promptTokensDetailsHoldsAllFields() {
        PromptTokensDetails d = new PromptTokensDetails(10, 20);
        assertEquals(10, d.audioTokens());
        assertEquals(20, d.cachedTokens());
    }
}
