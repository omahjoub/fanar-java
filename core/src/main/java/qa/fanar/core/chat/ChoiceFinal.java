package qa.fanar.core.chat;

import java.util.List;

/**
 * Choice inside a {@link DoneChunk}.
 *
 * <p>The wire format nests the accumulated references inside {@code choice.delta.references};
 * this record flattens one level. {@code finishReason} defaults to {@code "stop"} if {@code null}
 * — matching the wire-level default.</p>
 *
 * @param index        0-based position of this choice within the chunk's choices
 * @param finishReason finish reason (defaults to {@code "stop"} if input was {@code null})
 * @param references   authenticated references accumulated across the stream; never
 *                     {@code null}, may be empty
 */
public record ChoiceFinal(int index, String finishReason, List<Reference> references) {

    public ChoiceFinal {
        if (finishReason == null) {
            finishReason = "stop";
        }
        references = references == null ? List.of() : List.copyOf(references);
    }
}
