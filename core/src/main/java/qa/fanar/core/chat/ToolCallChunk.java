package qa.fanar.core.chat;

import java.util.List;
import java.util.Objects;

/**
 * Streaming event announcing that the model is invoking one or more tools server-side.
 *
 * <p>Fanar's tool-calling is server-initiated — there is no client-declared {@code tools}
 * parameter on the chat request. This chunk notifies the caller that a tool invocation is
 * happening internally (for example, retrieval by {@code Fanar-Sadiq}). Matching
 * {@link ToolResultChunk}s arrive once the tool returns.</p>
 *
 * @param id      completion id; must not be {@code null}
 * @param created server-side timestamp
 * @param model   wire-format model id; must not be {@code null}
 * @param choices per-choice tool-call deltas; must not be {@code null}, defensively copied
 *
 * @author Oussama Mahjoub
 */
public record ToolCallChunk(
        String id,
        long created,
        String model,
        List<ChoiceToolCall> choices
) implements StreamEvent {

    public ToolCallChunk {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(choices, "choices");
        choices = List.copyOf(choices);
    }
}
