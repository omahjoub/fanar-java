package qa.fanar.core.poems;

import java.util.Objects;
import java.util.Set;

/**
 * Typed identifier for a Fanar poetry-generation model — used by
 * {@code POST /v1/poems/generations}.
 *
 * <p>Mirrors the {@code PoemGenerationModels} schema in the OpenAPI spec, but open: callers can
 * target a new model via {@link #of(String)} the day Fanar ships it.</p>
 *
 * @param wireValue the exact string Fanar accepts in the {@code model} field
 *
 * @author Oussama Mahjoub
 */
public record PoemModel(String wireValue) {

    /** Fanar-Diwan — Arabic poetry-generation model. */
    public static final PoemModel FANAR_DIWAN = new PoemModel("Fanar-Diwan");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<PoemModel> KNOWN = Set.of(FANAR_DIWAN);

    public PoemModel {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new PoemModel(wireValue)}; provided for API symmetry. */
    public static PoemModel of(String wireValue) {
        return new PoemModel(wireValue);
    }
}
