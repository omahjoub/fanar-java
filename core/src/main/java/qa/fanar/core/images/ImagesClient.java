package qa.fanar.core.images;

import java.util.concurrent.CompletableFuture;

/**
 * Domain facade for the {@code /v1/images/generations} endpoint. Returned by
 * {@code FanarClient.images()}.
 *
 * <p>The endpoint requires additional Fanar authorization (per spec); if your API key isn't
 * authorized, both methods surface a {@code FanarAuthorizationException} (HTTP 403).</p>
 */
public interface ImagesClient {

    /**
     * Generate an image from the given prompt.
     *
     * @param request the prompt + model to use; must not be {@code null}
     * @return the response carrying the generated image(s) as base64-encoded bytes
     */
    ImageGenerationResponse generate(ImageGenerationRequest request);

    /**
     * Same as {@link #generate} but asynchronous. The returned future completes with the response
     * or exceptionally with a subtype of {@code FanarException}.
     */
    CompletableFuture<ImageGenerationResponse> generateAsync(ImageGenerationRequest request);
}
