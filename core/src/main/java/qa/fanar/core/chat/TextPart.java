package qa.fanar.core.chat;

import java.util.Objects;

/**
 * Plain-text content part. Valid in both {@link UserMessage}s and {@link AssistantMessage}s.
 *
 * @param text the text payload; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record TextPart(String text) implements UserContentPart, AssistantContentPart {

    public TextPart {
        Objects.requireNonNull(text, "text");
    }
}
