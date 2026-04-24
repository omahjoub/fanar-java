package qa.fanar.core.chat;

import java.util.Objects;

/**
 * Plain-text content part in a chat-completion response.
 *
 * <p>Distinct from the input-side {@link TextPart} even though the Java shape is identical, to
 * keep the input and output content hierarchies disjoint.</p>
 *
 * @param text the text payload; must not be {@code null}
 */
public record TextContent(String text) implements ResponseContent {

    public TextContent {
        Objects.requireNonNull(text, "text");
    }
}
