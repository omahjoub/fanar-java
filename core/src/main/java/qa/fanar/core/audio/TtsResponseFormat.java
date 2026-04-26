package qa.fanar.core.audio;

import java.util.Objects;
import java.util.Set;

/**
 * Audio container for the bytes Fanar returns from {@code POST /v1/audio/speech}. Mirrors the
 * {@code TTSResponseFormat} schema. Open: if Fanar adds a new format, callers can request it
 * via {@link #of(String)}.
 *
 * @param wireValue the exact string Fanar accepts (e.g. {@code "mp3"})
 *
 * @author Oussama Mahjoub
 */
public record TtsResponseFormat(String wireValue) {

    /** MPEG audio (server returns {@code Content-Type: audio/mpeg}). Default if unset. */
    public static final TtsResponseFormat MP3 = new TtsResponseFormat("mp3");

    /** Uncompressed PCM WAV (server returns {@code Content-Type: audio/wav}). */
    public static final TtsResponseFormat WAV = new TtsResponseFormat("wav");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<TtsResponseFormat> KNOWN = Set.of(MP3, WAV);

    public TtsResponseFormat {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new TtsResponseFormat(wireValue)}; provided for API symmetry. */
    public static TtsResponseFormat of(String wireValue) {
        return new TtsResponseFormat(wireValue);
    }
}
