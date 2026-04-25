package qa.fanar.core.translations;

import java.util.Objects;

/**
 * Request to translate text via {@code POST /v1/translations}.
 *
 * <p>The wire field for {@code langPair} is the single word {@code "langpair"} (no underscore);
 * the JSON adapter mixins handle this naming override so the Java side can stay camelCase.</p>
 *
 * <p>Spec note: the endpoint requires additional Fanar authorization and is not allowed by
 * default. If your API key isn't authorized, calls surface as a {@code FanarAuthorizationException}
 * (HTTP 403).</p>
 *
 * @param model         the translation model to use; must not be {@code null}
 * @param text          the text to translate (server limit: 4,000 words); must not be {@code null}
 * @param langPair      source-target language pair; must not be {@code null}
 * @param preprocessing how to preprocess text before translation; {@code null} → server default
 *                      (which is {@link TranslationPreprocessing#DEFAULT})
 */
public record TranslationRequest(
        TranslationModel model,
        String text,
        LanguagePair langPair,
        TranslationPreprocessing preprocessing
) {

    public TranslationRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(langPair, "langPair");
        // preprocessing is nullable — server applies default
    }

    /** Static factory for the common path: model + text + language pair, default preprocessing. */
    public static TranslationRequest of(TranslationModel model, String text, LanguagePair langPair) {
        return new TranslationRequest(model, text, langPair, null);
    }
}
