package qa.fanar.core.translations;

import java.util.Objects;

/**
 * Response from {@code POST /v1/translations}.
 *
 * @param id   unique identifier for this translation
 * @param text the translated text
 */
public record TranslationResponse(String id, String text) {

    public TranslationResponse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
    }
}
