package qa.fanar.core.audio;

import java.util.Objects;
import java.util.Set;

/**
 * Whether a voice in the {@code GET /v1/audio/voices} listing is a built-in public voice or a
 * personalized voice registered for the API key.
 *
 * <p>Mirrors the {@code type} enum on the OpenAPI {@code Voice} schema — but open: if Fanar adds
 * a new voice category, decoding still succeeds via {@link #of(String)} without waiting for an
 * SDK release.</p>
 *
 * @param wireValue the exact string Fanar uses on the wire for this voice type
 *
 * @author Oussama Mahjoub
 */
public record VoiceType(String wireValue) {

    /** A built-in voice available to every API key. */
    public static final VoiceType PUBLIC   = new VoiceType("public");

    /** A personalized voice registered for this API key via {@code AudioClient.createVoice(...)}. */
    public static final VoiceType PERSONAL = new VoiceType("personal");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<VoiceType> KNOWN = Set.of(PUBLIC, PERSONAL);

    public VoiceType {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new VoiceType(wireValue)}; provided for API symmetry with other types. */
    public static VoiceType of(String wireValue) {
        return new VoiceType(wireValue);
    }
}
