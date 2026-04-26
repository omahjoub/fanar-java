package qa.fanar.core.images;

import java.util.Objects;

/**
 * One generated image returned in {@link ImageGenerationResponse#data()}.
 *
 * <p>Per the OpenAPI spec, the only shape Fanar emits is base64-encoded bytes via
 * {@code b64_json}. If Fanar later adds a URL-output variant, this type would become a sealed
 * hierarchy ({@code ImageGenerationItem} → {@code Base64Item} / {@code UrlItem}); for now the
 * record stays flat to match what the server actually sends.</p>
 *
 * @param b64Json base64-encoded image bytes, ready to {@link java.util.Base64#getDecoder() decode}
 *
 * @author Oussama Mahjoub
 */
public record ImageGenerationItem(String b64Json) {

    public ImageGenerationItem {
        Objects.requireNonNull(b64Json, "b64Json");
    }
}
