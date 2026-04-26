package qa.fanar.core.poems;

import java.util.concurrent.CompletableFuture;

/**
 * Domain facade for the {@code /v1/poems/generations} endpoint. Returned by
 * {@code FanarClient.poems()}.
 *
 * <p>The endpoint requires additional Fanar authorization (per spec); if your API key isn't
 * authorized, both methods surface a {@code FanarAuthorizationException} (HTTP 403).</p>
 *
 * @author Oussama Mahjoub
 */
public interface PoemsClient {

    /**
     * Generate a poem from the given prompt.
     *
     * @param request the prompt + model to use; must not be {@code null}
     * @return the response carrying the generated poem
     */
    PoemGenerationResponse generate(PoemGenerationRequest request);

    /**
     * Same as {@link #generate} but asynchronous. The returned future completes with the response
     * or exceptionally with a subtype of {@code FanarException}.
     */
    CompletableFuture<PoemGenerationResponse> generateAsync(PoemGenerationRequest request);
}
