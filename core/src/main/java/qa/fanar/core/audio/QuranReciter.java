package qa.fanar.core.audio;

import java.util.Objects;
import java.util.Set;

/**
 * Quran reciter to use when calling {@code POST /v1/audio/speech} with the
 * {@link TtsModel#FANAR_SADIQ_TTS_1} model. Mirrors the {@code QuranReciters} schema; open
 * for new reciters via {@link #of(String)}.
 *
 * @param wireValue the exact string Fanar accepts in the {@code quran_reciter} field
 */
public record QuranReciter(String wireValue) {

    /** Abdul Basit — default reciter when {@code quranReciter} is unset. */
    public static final QuranReciter ABDUL_BASIT      = new QuranReciter("abdul-basit");

    /** Maher al-Muaiqly. */
    public static final QuranReciter MAHER_AL_MUAIQLY = new QuranReciter("maher-al-muaiqly");

    /** Mahmoud al-Husary. */
    public static final QuranReciter MAHMOUD_AL_HUSARY = new QuranReciter("mahmoud-al-husary");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<QuranReciter> KNOWN = Set.of(
            ABDUL_BASIT, MAHER_AL_MUAIQLY, MAHMOUD_AL_HUSARY);

    public QuranReciter {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new QuranReciter(wireValue)}; provided for API symmetry. */
    public static QuranReciter of(String wireValue) {
        return new QuranReciter(wireValue);
    }
}
