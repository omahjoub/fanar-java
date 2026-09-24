package qa.fanar.core.chat;

import java.util.List;
import java.util.Objects;

import qa.fanar.core.sadiq.DeepResearchEvent;

/**
 * Streaming event indicating an error occurred mid-stream.
 *
 * <p>Emitted when Fanar cannot continue producing output — for example because of a content
 * filter decision, a transient server error, or an upstream failure. The error description is
 * in {@code choices.get(0).content()}; {@code finishReason} is {@code "error"}. No further
 * events arrive after an {@code ErrorChunk}.</p>
 *
 * <p>Downstream consumers typically terminate their {@code Flow.Subscriber} after receiving
 * one; they do not attempt to continue the stream. Emitted by chat streams and by deep-research
 * streams alike, hence a member of both {@link StreamEvent} and {@link DeepResearchEvent}.</p>
 *
 * @param id      completion id; must not be {@code null}
 * @param created server-side timestamp
 * @param model   wire-format model id; must not be {@code null}
 * @param choices error-bearing choices; must not be {@code null}, defensively copied
 *
 * @author Oussama Mahjoub
 */
public record ErrorChunk(
        String id,
        long created,
        String model,
        List<ChoiceError> choices
) implements StreamEvent, DeepResearchEvent {

    public ErrorChunk {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(choices, "choices");
        choices = List.copyOf(choices);
    }
}
