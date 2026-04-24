package qa.fanar.core.chat;

import java.util.Objects;

/**
 * Video content part attached to a {@link UserMessage}.
 *
 * <p>The video is referenced by URL. Fanar's video content type is unusual among LLM providers —
 * first-class video URLs on the chat endpoint, rather than being relegated to a separate media
 * upload step.</p>
 *
 * @param url the video URL; must not be {@code null}
 */
public record VideoPart(String url) implements UserContentPart {

    public VideoPart {
        Objects.requireNonNull(url, "url");
    }
}
