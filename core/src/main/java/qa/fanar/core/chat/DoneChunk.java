package qa.fanar.core.chat;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Terminal streaming event — the model is done generating.
 *
 * <p>Carries the final {@link CompletionUsage} (when provided), any accumulated references from
 * the streaming session, and arbitrary server-side metadata. After a {@code DoneChunk}, no more
 * events arrive on the publisher.</p>
 *
 * @param id       completion id; must not be {@code null}
 * @param created  server-side timestamp
 * @param model    wire-format model id; must not be {@code null}
 * @param choices  final choice records carrying accumulated references; defensively copied
 * @param usage    token-usage summary; may be {@code null}
 * @param metadata opaque Fanar-specific metadata; never {@code null}, may be empty
 */
public record DoneChunk(
        String id,
        long created,
        String model,
        List<ChoiceFinal> choices,
        CompletionUsage usage,
        Map<String, Object> metadata
) implements StreamEvent {

    public DoneChunk {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(choices, "choices");
        choices = List.copyOf(choices);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
