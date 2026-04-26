package qa.fanar.core.chat;

import java.util.Objects;

/**
 * System message — the prompt framing at the top of the conversation.
 *
 * <p>Fanar's system role accepts plain text content. Use {@link #of(String)} for the common case
 * where no {@code name} is needed, or the canonical record constructor to include a name.</p>
 *
 * @param content the system prompt; must not be {@code null}
 * @param name    optional speaker name (nullable)
 *
 * @author Oussama Mahjoub
 */
public record SystemMessage(String content, String name) implements Message {

    public SystemMessage {
        Objects.requireNonNull(content, "content");
    }

    /** Convenience factory for a system message with no {@code name}. */
    public static SystemMessage of(String content) {
        return new SystemMessage(content, null);
    }
}
