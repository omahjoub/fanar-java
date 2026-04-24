package qa.fanar.core.chat;

import java.util.Objects;

/**
 * Choice inside a {@link ToolResultChunk}.
 *
 * <p>The wire format nests the result inside {@code choice.delta.tool_result}; this record
 * flattens one level.</p>
 *
 * @param index        0-based position of this choice within the chunk's choices
 * @param finishReason finish reason; {@code null} while generation continues
 * @param toolResult   the tool invocation outcome; must not be {@code null}
 */
public record ChoiceToolResult(int index, String finishReason, ToolResultData toolResult) {

    public ChoiceToolResult {
        Objects.requireNonNull(toolResult, "toolResult");
    }
}
