package qa.fanar.core.chat;

import java.util.Objects;

/**
 * Image content part in a chat-completion response.
 *
 * <p>The image is referenced by URL only — output has no {@code detail} hint (that was an
 * input-side concern; see {@link ImagePart}).</p>
 *
 * @param url the image URL; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record ImageContent(String url) implements ResponseContent {

    public ImageContent {
        Objects.requireNonNull(url, "url");
    }
}
