package qa.fanar.core.chat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import qa.fanar.core.sadiq.DeepResearchEvent;

/**
 * Terminal streaming event — the model is done generating.
 *
 * <p>Carries the final {@link CompletionUsage} (when provided), any accumulated references from
 * the streaming session, and arbitrary server-side metadata. After a {@code DoneChunk}, no more
 * events arrive on the publisher. Emitted by chat streams and by deep-research streams alike,
 * hence a member of both {@link StreamEvent} and {@link DeepResearchEvent}; a deep-research run
 * ends with one whose {@code metadata} summarises the run (depth, elapsed seconds, source counts)
 * and whose {@code usage} is often absent.</p>
 *
 * @param id       completion id; must not be {@code null}
 * @param created  server-side timestamp
 * @param model    wire-format model id; may be {@code null} when the server omits it
 * @param choices  final choice records carrying accumulated references; defensively copied
 * @param usage    token-usage summary; may be {@code null}
 * @param metadata opaque Fanar-specific metadata; never {@code null}, may be empty, and its
 *                 values may be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record DoneChunk(
        String id,
        long created,
        String model,
        List<ChoiceFinal> choices,
        CompletionUsage usage,
        Map<String, Object> metadata
) implements StreamEvent, DeepResearchEvent {

    public DoneChunk {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(choices, "choices");
        choices = List.copyOf(choices);
        // Not Map.copyOf: the server's summary may carry null values, which that copy rejects.
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
