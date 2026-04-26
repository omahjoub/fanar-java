package qa.fanar.core.chat;

import java.util.List;
import java.util.Objects;

/**
 * Streaming event carrying the result of a server-side tool invocation previously announced
 * by a {@link ToolCallChunk}.
 *
 * @param id      completion id; must not be {@code null}
 * @param created server-side timestamp
 * @param model   wire-format model id; must not be {@code null}
 * @param choices per-choice tool-result deltas; must not be {@code null}, defensively copied
 *
 * @author Oussama Mahjoub
 */
public record ToolResultChunk(
        String id,
        long created,
        String model,
        List<ChoiceToolResult> choices
) implements StreamEvent {

    public ToolResultChunk {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(choices, "choices");
        choices = List.copyOf(choices);
    }
}
