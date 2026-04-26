package qa.fanar.core.chat;

import java.util.Objects;

/**
 * Choice inside a {@link TokenChunk}.
 *
 * <p>The wire format nests the token delta inside {@code choice.delta.content}; this record
 * flattens that one level so the token is available via {@link #content()} directly.</p>
 *
 * @param index        0-based position of this choice within the chunk's choices
 * @param finishReason finish reason if this chunk is the last for the choice; {@code null}
 *                     while generation continues
 * @param content      the token delta; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record ChoiceToken(int index, String finishReason, String content) {

    public ChoiceToken {
        Objects.requireNonNull(content, "content");
    }
}
