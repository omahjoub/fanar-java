package qa.fanar.core.chat;

import java.util.Objects;

/**
 * A previous-turn user message paired with the assistant's reasoning trace.
 *
 * <p>Companion to {@link ThinkingMessage}. Some thinking-protocol workflows preserve both the
 * user's original question and the assistant's reasoning in a single re-serializable format —
 * {@code ThinkingUserMessage} carries the user-side half.</p>
 *
 * @param content the user-message text recorded alongside the thinking trace; must not be
 *                {@code null}
 *
 * @author Oussama Mahjoub
 */
public record ThinkingUserMessage(String content) implements Message {

    public ThinkingUserMessage {
        Objects.requireNonNull(content, "content");
    }

    /** Convenience factory. */
    public static ThinkingUserMessage of(String content) {
        return new ThinkingUserMessage(content);
    }
}
