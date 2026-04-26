package qa.fanar.core.models;

import java.util.List;
import java.util.Objects;

/**
 * Response shape for {@code GET /v1/models}. Mirrors the {@code ModelsResponse} schema in the
 * Fanar OpenAPI spec.
 *
 * <p>{@code models} is defensively copied on construction and returned as an unmodifiable view.</p>
 *
 * @param id     unique identifier for this list response
 * @param models models the API key has access to
 *
 * @author Oussama Mahjoub
 */
public record ModelsResponse(
        String id,
        List<AvailableModel> models
) {

    public ModelsResponse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(models, "models");
        models = List.copyOf(models);
    }
}
