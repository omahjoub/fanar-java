package qa.fanar.core.moderations;

import java.util.Objects;
import java.util.Set;

/**
 * Typed identifier for a Fanar moderation/safety model — used by {@code POST /v1/moderations}.
 *
 * <p>Mirrors the {@code ModerationModels} schema in the Fanar OpenAPI spec, but open: callers
 * can target a new moderation model via {@link #of(String)} the day Fanar ships it without
 * waiting for an SDK release.</p>
 *
 * @param wireValue the exact string Fanar accepts in the {@code model} field
 */
public record ModerationModel(String wireValue) {

    /** FanarGuard v2 — safety + cultural-awareness scoring for prompt/response pairs. */
    public static final ModerationModel FANAR_GUARD_2 = new ModerationModel("Fanar-Guard-2");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<ModerationModel> KNOWN = Set.of(FANAR_GUARD_2);

    public ModerationModel {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new ModerationModel(wireValue)}; provided for API symmetry. */
    public static ModerationModel of(String wireValue) {
        return new ModerationModel(wireValue);
    }
}
