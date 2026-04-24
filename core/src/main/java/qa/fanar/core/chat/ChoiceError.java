package qa.fanar.core.chat;

import java.util.Objects;

/**
 * Choice inside an {@link ErrorChunk}.
 *
 * <p>The wire format nests the error description inside {@code choice.delta.content} and sets
 * {@code finishReason} to {@code "error"} by default; this record flattens one level. The
 * {@code content} string is the human-readable error description.</p>
 *
 * @param index        0-based position of this choice within the chunk's choices
 * @param finishReason finish reason (defaults to {@code "error"} if input was {@code null})
 * @param content      error description; must not be {@code null}
 */
public record ChoiceError(int index, String finishReason, String content) {

    public ChoiceError {
        Objects.requireNonNull(content, "content");
        if (finishReason == null) {
            finishReason = "error";
        }
    }
}
