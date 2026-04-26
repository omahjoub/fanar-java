package qa.fanar.core.chat;

import java.util.List;
import java.util.Objects;

/**
 * User message — a turn from the human (or upstream system) in the conversation.
 *
 * <p>Content is a non-empty list of {@link UserContentPart}s: any mix of text, image, and video
 * parts. The canonical constructor takes defensive copies; accessors return unmodifiable views.
 * Use {@link #of(String)} for the common text-only case.</p>
 *
 * @param content non-empty list of content parts; must not be {@code null} or empty
 * @param name    optional speaker name (nullable)
 *
 * @author Oussama Mahjoub
 */
public record UserMessage(List<UserContentPart> content, String name) implements Message {

    public UserMessage {
        Objects.requireNonNull(content, "content");
        content = List.copyOf(content);
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
    }

    /** Convenience factory for a text-only user message. */
    public static UserMessage of(String text) {
        return new UserMessage(List.of(new TextPart(text)), null);
    }
}
