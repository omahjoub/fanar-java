package qa.fanar.core.images;

import java.util.Objects;

/**
 * Request to generate an image via {@code POST /v1/images/generations}.
 *
 * <p>Spec note: the endpoint requires additional Fanar authorization and is not allowed by
 * default. If your API key isn't authorized, calls surface as a {@code FanarAuthorizationException}
 * (HTTP 403).</p>
 *
 * @param model  the image-generation model to use; must not be {@code null}
 * @param prompt a natural-language description of the desired image; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record ImageGenerationRequest(ImageModel model, String prompt) {

    public ImageGenerationRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(prompt, "prompt");
    }

    /** Static factory — argument order matches the JSON wire shape. */
    public static ImageGenerationRequest of(ImageModel model, String prompt) {
        return new ImageGenerationRequest(model, prompt);
    }
}
