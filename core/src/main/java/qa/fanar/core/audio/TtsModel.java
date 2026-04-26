package qa.fanar.core.audio;

import java.util.Objects;
import java.util.Set;

/**
 * Typed identifier for a Fanar text-to-speech model — used by
 * {@link TextToSpeechRequest#model()}.
 *
 * <p>Mirrors the {@code TTSModels} schema in the OpenAPI spec, but open: callers can target a
 * new model via {@link #of(String)} the day Fanar ships it.</p>
 *
 * @param wireValue the exact string Fanar accepts in the {@code model} field
 *
 * @author Oussama Mahjoub
 */
public record TtsModel(String wireValue) {

    /** Fanar-Aura-TTS-2 — primary text-to-speech model for English/Arabic voices. */
    public static final TtsModel FANAR_AURA_TTS_2  = new TtsModel("Fanar-Aura-TTS-2");

    /** Fanar-Sadiq-TTS-1 — Quran-specialised TTS, requires {@code quranReciter}. */
    public static final TtsModel FANAR_SADIQ_TTS_1 = new TtsModel("Fanar-Sadiq-TTS-1");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<TtsModel> KNOWN = Set.of(FANAR_AURA_TTS_2, FANAR_SADIQ_TTS_1);

    public TtsModel {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new TtsModel(wireValue)}; provided for API symmetry. */
    public static TtsModel of(String wireValue) {
        return new TtsModel(wireValue);
    }
}
