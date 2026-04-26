package qa.fanar.core.chat;

import java.util.Objects;

/**
 * One tool invocation carried inside a {@link ToolCallChunk} choice.
 *
 * <p>Distinct from {@link ToolCall} on the response-side assistant message: this type is the
 * streaming delta (invocation-in-progress), whereas {@code ToolCall} is the finalized record
 * on a {@link ChatMessage}. They carry overlapping but not identical data.</p>
 *
 * @param index    position of this tool call in the chunk's tool_calls list
 * @param id       unique invocation id; must not be {@code null}
 * @param type     tool type — defaults to {@code "function"} if {@code null}
 * @param function the function being invoked; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record ToolCallData(int index, String id, String type, FunctionData function) {

    public ToolCallData {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(function, "function");
        if (type == null) {
            type = "function";
        }
    }
}
