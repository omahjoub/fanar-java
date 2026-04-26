package qa.fanar.core.images;

import java.util.Objects;
import java.util.Set;

/**
 * Typed identifier for a Fanar image-generation model — used by
 * {@code POST /v1/images/generations}.
 *
 * <p>Mirrors the {@code ImageGenerationModels} schema in the OpenAPI spec, but open: callers
 * can target a new model via {@link #of(String)} the day Fanar ships it.</p>
 *
 * @param wireValue the exact string Fanar accepts in the {@code model} field
 *
 * @author Oussama Mahjoub
 */
public record ImageModel(String wireValue) {

    /** Fanar-Oryx-IG-2 — Arabic-aware image generation model (the {@code IG} stands for "image generation"). */
    public static final ImageModel FANAR_ORYX_IG_2 = new ImageModel("Fanar-Oryx-IG-2");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<ImageModel> KNOWN = Set.of(FANAR_ORYX_IG_2);

    public ImageModel {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new ImageModel(wireValue)}; provided for API symmetry. */
    public static ImageModel of(String wireValue) {
        return new ImageModel(wireValue);
    }
}
