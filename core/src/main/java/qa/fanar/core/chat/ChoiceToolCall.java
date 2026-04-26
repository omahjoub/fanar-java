package qa.fanar.core.chat;

import java.util.List;
import java.util.Objects;

/**
 * Choice inside a {@link ToolCallChunk}.
 *
 * <p>The wire format nests the tool-call list inside {@code choice.delta.tool_calls}; this
 * record flattens one level.</p>
 *
 * @param index        0-based position of this choice within the chunk's choices
 * @param finishReason finish reason; {@code null} while generation continues
 * @param toolCalls    tool-call deltas; must not be {@code null}, defensively copied
 *
 * @author Oussama Mahjoub
 */
public record ChoiceToolCall(int index, String finishReason, List<ToolCallData> toolCalls) {

    public ChoiceToolCall {
        Objects.requireNonNull(toolCalls, "toolCalls");
        toolCalls = List.copyOf(toolCalls);
    }
}
