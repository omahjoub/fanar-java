package qa.fanar.core.poems;

import java.util.Objects;

/**
 * Request to generate a poem via {@code POST /v1/poems/generations}.
 *
 * <p>Spec note: the endpoint requires additional Fanar authorization and is not allowed by
 * default. If your API key isn't authorized, calls surface as a {@code FanarAuthorizationException}
 * (HTTP 403).</p>
 *
 * @param model  the poem-generation model to use; must not be {@code null}
 * @param prompt a natural-language description of the desired poem; must not be {@code null}
 */
public record PoemGenerationRequest(PoemModel model, String prompt) {

    public PoemGenerationRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(prompt, "prompt");
    }

    /** Static factory — argument order matches the JSON wire shape. */
    public static PoemGenerationRequest of(PoemModel model, String prompt) {
        return new PoemGenerationRequest(model, prompt);
    }
}
