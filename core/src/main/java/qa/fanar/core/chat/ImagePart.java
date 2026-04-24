package qa.fanar.core.chat;

import java.util.Objects;

/**
 * Image content part attached to a {@link UserMessage}.
 *
 * <p>The image is referenced by URL — either an HTTPS URL or a base64 data URI (for example
 * {@code data:image/png;base64,...}). Vision-capable chat models (notably
 * {@code Fanar-Oryx-IVU-2}) consume these parts.</p>
 *
 * @param url    the image URL or data URI; must not be {@code null}
 * @param detail detail hint; {@code null} means the server chooses ({@link ImageDetail#AUTO})
 */
public record ImagePart(String url, ImageDetail detail) implements UserContentPart {

    public ImagePart {
        Objects.requireNonNull(url, "url");
    }

    /** Convenience factory with {@code detail} unspecified. */
    public static ImagePart of(String url) {
        return new ImagePart(url, null);
    }
}
