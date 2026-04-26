package qa.fanar.core.chat;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The response returned by Fanar's {@code POST /v1/chat/completions} endpoint (non-streaming).
 *
 * <p>The {@code model} field is returned as a {@link String} rather than a {@link ChatModel}
 * record, because callers don't always need the typed wrapper. When they do, they can pass
 * {@code response.model()} through {@link ChatModel#of(String)} — it accepts any wire value,
 * so callers are not blocked by SDK release cadence when Fanar adds a new model.</p>
 *
 * <p>Collections are defensively copied on construction and returned as unmodifiable views. Null
 * input for optional collections and for the {@code metadata} map is normalized to empty so
 * accessors never return {@code null}.</p>
 *
 * @param id       Fanar's unique id for this completion; must not be {@code null}
 * @param choices  completion choices; must not be {@code null}, may be empty
 * @param created  server-side creation time (Unix seconds)
 * @param model    wire-format model id the server picked; must not be {@code null}
 * @param usage    token-usage breakdown; {@code null} when the server did not provide one
 * @param metadata opaque Fanar-specific metadata; never {@code null}, may be empty
 *
 * @author Oussama Mahjoub
 */
public record ChatResponse(
        String id,
        List<ChatChoice> choices,
        long created,
        String model,
        CompletionUsage usage,
        Map<String, Object> metadata
) {

    public ChatResponse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(choices, "choices");
        choices = List.copyOf(choices);
        Objects.requireNonNull(model, "model");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
