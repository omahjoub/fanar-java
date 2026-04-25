package qa.fanar.core.images;

import java.util.List;
import java.util.Objects;

/**
 * Response from {@code POST /v1/images/generations}.
 *
 * <p>{@code data} is defensively copied on construction and returned as an unmodifiable view.
 * In practice Fanar emits a single image per request despite the list-shaped wire schema.</p>
 *
 * @param id      unique identifier for this generation
 * @param created Unix epoch seconds at which the image was generated
 * @param data    generated images (typically size 1)
 */
public record ImageGenerationResponse(String id, long created, List<ImageGenerationItem> data) {

    public ImageGenerationResponse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(data, "data");
        data = List.copyOf(data);
    }
}
