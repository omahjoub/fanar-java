package qa.fanar.core.translations;

import java.util.concurrent.CompletableFuture;

/**
 * Domain facade for the {@code /v1/translations} endpoint. Returned by
 * {@code FanarClient.translations()}.
 *
 * <p>The endpoint requires additional Fanar authorization (per spec); if your API key isn't
 * authorized, both methods surface a {@code FanarAuthorizationException} (HTTP 403).</p>
 */
public interface TranslationsClient {

    /**
     * Translate {@code request.text()} using {@code request.langPair()} and {@code request.model()}.
     *
     * @param request the translation request; must not be {@code null}
     * @return the translation response carrying the translated text
     */
    TranslationResponse translate(TranslationRequest request);

    /**
     * Same as {@link #translate} but asynchronous. The returned future completes with the
     * response or exceptionally with a subtype of {@code FanarException}.
     */
    CompletableFuture<TranslationResponse> translateAsync(TranslationRequest request);
}
