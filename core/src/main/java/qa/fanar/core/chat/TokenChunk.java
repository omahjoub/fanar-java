package qa.fanar.core.chat;

import java.util.List;
import java.util.Objects;

/**
 * Streaming event carrying one or more token deltas produced by the model.
 *
 * <p>The bulk of a streaming response is a sequence of these, one per token or small group of
 * tokens. Accumulating the {@code choices.get(0).content()} strings across a stream reconstructs
 * the model's textual output.</p>
 *
 * @param id      completion id; must not be {@code null}
 * @param created server-side timestamp
 * @param model   wire-format model id; must not be {@code null}
 * @param choices per-choice deltas; must not be {@code null}, defensively copied
 */
public record TokenChunk(
        String id,
        long created,
        String model,
        List<ChoiceToken> choices
) implements StreamEvent {

    public TokenChunk {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(choices, "choices");
        choices = List.copyOf(choices);
    }
}
