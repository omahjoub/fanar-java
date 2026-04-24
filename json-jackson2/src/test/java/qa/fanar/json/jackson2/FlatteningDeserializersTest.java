package qa.fanar.json.jackson2;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.ChoiceError;
import qa.fanar.core.chat.ChoiceFinal;
import qa.fanar.core.chat.ChoiceToken;
import qa.fanar.core.chat.ChoiceToolCall;
import qa.fanar.core.chat.ChoiceToolResult;
import qa.fanar.core.chat.ProgressChunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlatteningDeserializersTest {

    private final Jackson2FanarJsonCodec codec = new Jackson2FanarJsonCodec();

    // --- ChoiceToken ----------------------------------------------------------------------

    @Test
    void choiceToken_unwrapsDeltaContent() throws IOException {
        ChoiceToken c = decode(ChoiceToken.class,
                "{\"index\":1,\"finish_reason\":null,\"delta\":{\"content\":\"hi\"}}");
        assertEquals(1, c.index());
        assertNull(c.finishReason());
        assertEquals("hi", c.content());
    }

    @Test
    void choiceToken_missingDeltaDefaultsContentToEmpty() throws IOException {
        ChoiceToken c = decode(ChoiceToken.class, "{\"index\":0,\"finish_reason\":\"stop\"}");
        assertEquals(0, c.index());
        assertEquals("stop", c.finishReason());
        assertEquals("", c.content(), "record requires non-null content; empty is the safe fallback");
    }

    @Test
    void choiceToken_nullDeltaDefaultsContentToEmpty() throws IOException {
        ChoiceToken c = decode(ChoiceToken.class, "{\"index\":0,\"delta\":null}");
        assertEquals("", c.content());
    }

    @Test
    void choiceToken_deltaWithoutContentDefaultsToEmpty() throws IOException {
        ChoiceToken c = decode(ChoiceToken.class, "{\"delta\":{\"other\":\"x\"}}");
        assertEquals("", c.content());
    }

    @Test
    void choiceToken_explicitNullIndexDefaultsToZero() throws IOException {
        ChoiceToken c = decode(ChoiceToken.class, "{\"index\":null,\"delta\":{\"content\":\"x\"}}");
        assertEquals(0, c.index());
    }

    // --- ChoiceToolCall -------------------------------------------------------------------

    @Test
    void choiceToolCall_unwrapsDeltaToolCallsArray() throws IOException {
        ChoiceToolCall c = decode(ChoiceToolCall.class, """
                {
                  "index": 0,
                  "finish_reason": "tool_calls",
                  "delta": {
                    "tool_calls": [
                      {"index": 0, "id": "t_1", "type": "function",
                       "function": {"name": "search", "arguments": "{}"}},
                      {"index": 1, "id": "t_2", "type": "function",
                       "function": {"name": "quote", "arguments": "{}"}}
                    ]
                  }
                }
                """);
        assertEquals(2, c.toolCalls().size());
        assertEquals("t_1", c.toolCalls().getFirst().id());
        assertEquals("search", c.toolCalls().getFirst().function().name());
    }

    @Test
    void choiceToolCall_missingDeltaYieldsEmptyList() throws IOException {
        ChoiceToolCall c = decode(ChoiceToolCall.class, "{\"index\":0}");
        assertTrue(c.toolCalls().isEmpty());
    }

    @Test
    void choiceToolCall_nullDeltaYieldsEmptyList() throws IOException {
        ChoiceToolCall c = decode(ChoiceToolCall.class, "{\"delta\":null}");
        assertTrue(c.toolCalls().isEmpty());
    }

    @Test
    void choiceToolCall_missingToolCallsKeyYieldsEmptyList() throws IOException {
        ChoiceToolCall c = decode(ChoiceToolCall.class, "{\"delta\":{\"other\":1}}");
        assertTrue(c.toolCalls().isEmpty());
    }

    @Test
    void choiceToolCall_nonArrayToolCallsYieldsEmptyList() throws IOException {
        ChoiceToolCall c = decode(ChoiceToolCall.class, "{\"delta\":{\"tool_calls\":\"not-an-array\"}}");
        assertTrue(c.toolCalls().isEmpty());
    }

    // --- ChoiceToolResult -----------------------------------------------------------------

    @Test
    void choiceToolResult_unwrapsDeltaToolResult() throws IOException {
        ChoiceToolResult c = decode(ChoiceToolResult.class, """
                {
                  "index": 0,
                  "finish_reason": null,
                  "delta": {
                    "tool_result": {
                      "id": "t_1",
                      "name": "search",
                      "arguments": {},
                      "result": "{\\"hits\\":[]}",
                      "is_error": false
                    }
                  }
                }
                """);
        assertEquals("t_1", c.toolResult().id());
        assertEquals("search", c.toolResult().name());
    }

    @Test
    void choiceToolResult_missingToolResultFieldFailsLoudly() {
        // Classifier invariant broken — the record requires a non-null toolResult.
        assertThrows(NullPointerException.class, () ->
                decode(ChoiceToolResult.class, "{\"delta\":{\"other\":1}}"));
    }

    @Test
    void choiceToolResult_nullDeltaFailsLoudly() {
        assertThrows(NullPointerException.class, () ->
                decode(ChoiceToolResult.class, "{\"delta\":null}"));
    }

    @Test
    void choiceToolResult_explicitNullToolResultFailsLoudly() {
        // delta is present, tool_result key is present but JSON null — still invalid input.
        assertThrows(NullPointerException.class, () ->
                decode(ChoiceToolResult.class, "{\"delta\":{\"tool_result\":null}}"));
    }

    // --- ChoiceFinal ----------------------------------------------------------------------

    @Test
    void choiceFinal_unwrapsDeltaReferences() throws IOException {
        ChoiceFinal c = decode(ChoiceFinal.class, """
                {
                  "index": 0,
                  "finish_reason": "stop",
                  "delta": {
                    "references": [
                      {"index": 0, "number": 1, "source": "Book", "content": "quote"}
                    ]
                  }
                }
                """);
        assertEquals("stop", c.finishReason());
        assertEquals(1, c.references().size());
    }

    @Test
    void choiceFinal_missingDeltaYieldsEmptyReferences() throws IOException {
        ChoiceFinal c = decode(ChoiceFinal.class, "{\"index\":0}");
        assertTrue(c.references().isEmpty());
        assertEquals("stop", c.finishReason(), "record defaults finish_reason to \"stop\" when null");
    }

    @Test
    void choiceFinal_nullDeltaYieldsEmptyReferences() throws IOException {
        ChoiceFinal c = decode(ChoiceFinal.class, "{\"delta\":null}");
        assertTrue(c.references().isEmpty());
    }

    // --- ChoiceError ----------------------------------------------------------------------

    @Test
    void choiceError_unwrapsDeltaContent() throws IOException {
        ChoiceError c = decode(ChoiceError.class,
                "{\"index\":0,\"finish_reason\":\"error\",\"delta\":{\"content\":\"boom\"}}");
        assertEquals("boom", c.content());
        assertEquals("error", c.finishReason());
    }

    @Test
    void choiceError_missingDeltaDefaultsToEmptyContent() throws IOException {
        ChoiceError c = decode(ChoiceError.class, "{\"index\":0}");
        assertEquals("", c.content());
        assertEquals("error", c.finishReason(), "record defaults finish_reason to \"error\"");
    }

    @Test
    void choiceError_nullDeltaDefaultsToEmptyContent() throws IOException {
        ChoiceError c = decode(ChoiceError.class, "{\"delta\":null}");
        assertEquals("", c.content());
    }

    // --- ProgressChunk --------------------------------------------------------------------

    @Test
    void progressChunk_unwrapsProgressMessage() throws IOException {
        ProgressChunk c = decode(ProgressChunk.class, """
                {
                  "id": "c_1",
                  "created": 1700000000,
                  "model": "Fanar-Sadiq",
                  "progress": {
                    "message": {"en": "searching", "ar": "البحث"}
                  }
                }
                """);
        assertEquals("c_1", c.id());
        assertEquals(1_700_000_000L, c.created());
        assertEquals("Fanar-Sadiq", c.model());
        assertEquals("searching", c.message().en());
        assertEquals("البحث", c.message().ar());
    }

    @Test
    void progressChunk_missingProgressYieldsNullMessage() {
        assertThrows(NullPointerException.class, () ->
                decode(ProgressChunk.class, "{\"id\":\"c\",\"created\":0,\"model\":\"m\"}"));
    }

    @Test
    void progressChunk_nullProgressYieldsNullMessage() {
        assertThrows(NullPointerException.class, () ->
                decode(ProgressChunk.class, "{\"id\":\"c\",\"created\":0,\"model\":\"m\",\"progress\":null}"));
    }

    @Test
    void progressChunk_nullMessageInProgressFailsLoudly() {
        assertThrows(NullPointerException.class, () ->
                decode(ProgressChunk.class,
                        "{\"id\":\"c\",\"created\":0,\"model\":\"m\",\"progress\":{\"message\":null}}"));
    }

    @Test
    void progressChunk_missingIdAndModelFailsLoudly() {
        assertThrows(NullPointerException.class, () ->
                decode(ProgressChunk.class,
                        "{\"created\":0,\"progress\":{\"message\":{\"en\":\"x\",\"ar\":\"ي\"}}}"));
    }

    @Test
    void progressChunk_explicitNullIdFailsLoudly() {
        assertThrows(NullPointerException.class, () ->
                decode(ProgressChunk.class,
                        "{\"id\":null,\"created\":0,\"model\":\"m\","
                                + "\"progress\":{\"message\":{\"en\":\"x\",\"ar\":\"ي\"}}}"));
    }

    @Test
    void progressChunk_explicitNullModelFailsLoudly() {
        assertThrows(NullPointerException.class, () ->
                decode(ProgressChunk.class,
                        "{\"id\":\"c\",\"created\":0,\"model\":null,"
                                + "\"progress\":{\"message\":{\"en\":\"x\",\"ar\":\"ي\"}}}"));
    }

    // --- helpers

    private <T> T decode(Class<T> type, String json) throws IOException {
        return codec.decode(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), type);
    }
}
