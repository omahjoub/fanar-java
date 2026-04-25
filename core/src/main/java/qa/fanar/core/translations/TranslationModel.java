package qa.fanar.core.translations;

import java.util.Objects;
import java.util.Set;

/**
 * Typed identifier for a Fanar translation model — used by {@code POST /v1/translations}.
 *
 * <p>Mirrors the {@code TranslationModels} schema in the OpenAPI spec, but open: callers can
 * target a new translation model via {@link #of(String)} the day Fanar ships it.</p>
 *
 * @param wireValue the exact string Fanar accepts in the {@code model} field
 */
public record TranslationModel(String wireValue) {

    /** Shaheen v1 — Fanar's machine-translation model (Arabic ↔ English). */
    public static final TranslationModel FANAR_SHAHEEN_MT_1 = new TranslationModel("Fanar-Shaheen-MT-1");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<TranslationModel> KNOWN = Set.of(FANAR_SHAHEEN_MT_1);

    public TranslationModel {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new TranslationModel(wireValue)}; provided for API symmetry. */
    public static TranslationModel of(String wireValue) {
        return new TranslationModel(wireValue);
    }
}
