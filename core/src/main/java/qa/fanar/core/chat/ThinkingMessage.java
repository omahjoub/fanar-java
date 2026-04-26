package qa.fanar.core.chat;

import java.util.Objects;

/**
 * A previous-turn reasoning trace recorded by the assistant.
 *
 * <p>Part of Fanar's role-based thinking protocol on {@code Fanar-C-2-27B}. When a caller wants
 * the model to reason over a conversation that already contains earlier thinking traces, those
 * traces are replayed as {@code ThinkingMessage}s — distinct from {@link AssistantMessage}
 * because they represent the model's private reasoning, not user-visible output.</p>
 *
 * @param content the reasoning trace text; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record ThinkingMessage(String content) implements Message {

    public ThinkingMessage {
        Objects.requireNonNull(content, "content");
    }

    /** Convenience factory. */
    public static ThinkingMessage of(String content) {
        return new ThinkingMessage(content);
    }
}
