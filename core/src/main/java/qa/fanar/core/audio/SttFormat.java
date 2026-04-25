package qa.fanar.core.audio;

import java.util.Objects;
import java.util.Set;

/**
 * Output format requested from {@code POST /v1/audio/transcriptions}. Mirrors the
 * {@code STTFormat} schema; open via {@link #of(String)}.
 *
 * <p>Spec note: {@link SttModel#FANAR_AURA_STT_1} only supports {@link #TEXT}; the long-form
 * model accepts all three. The server enforces compatibility, surfacing a typed exception when
 * the requested combination is invalid.</p>
 *
 * @param wireValue the exact string Fanar accepts on the wire
 */
public record SttFormat(String wireValue) {

    /** Plain text — the server returns {@code SpeechToTextResponse.Text}. */
    public static final SttFormat TEXT = new SttFormat("text");

    /** SubRip subtitle format — the server returns {@code SpeechToTextResponse.Srt}. */
    public static final SttFormat SRT  = new SttFormat("srt");

    /** Structured JSON with per-segment timings — server returns {@code SpeechToTextResponse.Json}. */
    public static final SttFormat JSON = new SttFormat("json");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<SttFormat> KNOWN = Set.of(TEXT, SRT, JSON);

    public SttFormat {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new SttFormat(wireValue)}; provided for API symmetry. */
    public static SttFormat of(String wireValue) {
        return new SttFormat(wireValue);
    }
}
