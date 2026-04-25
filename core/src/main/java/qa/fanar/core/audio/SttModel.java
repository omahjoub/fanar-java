package qa.fanar.core.audio;

import java.util.Objects;
import java.util.Set;

/**
 * Typed identifier for a Fanar speech-to-text model — used by
 * {@link TranscriptionRequest#model()}.
 *
 * <p>Mirrors the {@code STTModels} schema in the OpenAPI spec, but open: callers can target a
 * new model via {@link #of(String)} the day Fanar ships it.</p>
 *
 * @param wireValue the exact string Fanar accepts in the {@code model} field
 */
public record SttModel(String wireValue) {

    /** Fanar-Aura-STT-1 — short audio clips, up to ~20–30 seconds. */
    public static final SttModel FANAR_AURA_STT_1    = new SttModel("Fanar-Aura-STT-1");

    /** Fanar-Aura-STT-LF-1 — "long-form" model for longer audio files. */
    public static final SttModel FANAR_AURA_STT_LF_1 = new SttModel("Fanar-Aura-STT-LF-1");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<SttModel> KNOWN = Set.of(FANAR_AURA_STT_1, FANAR_AURA_STT_LF_1);

    public SttModel {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new SttModel(wireValue)}; provided for API symmetry. */
    public static SttModel of(String wireValue) {
        return new SttModel(wireValue);
    }
}
