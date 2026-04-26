package qa.fanar.core.audio;

import java.util.Objects;
import java.util.Set;

/**
 * Typed identifier for a TTS voice — used by {@code TextToSpeechRequest.voice()} and returned
 * by {@code AudioClient.listVoices()}.
 *
 * <p>Mirrors the inline {@code voice} enum on the OpenAPI {@code TextToSpeechRequest} but open
 * by design: callers can also use a personalized voice they previously created via
 * {@code AudioClient.createVoice(...)}, whose name is not in {@link #KNOWN}. Pass any voice name
 * via {@link #of(String)}.</p>
 *
 * @param wireValue the exact string Fanar accepts in the {@code voice} field
 *
 * @author Oussama Mahjoub
 */
public record Voice(String wireValue) {

    /** Female, British accent — English. */
    public static final Voice AMELIA = new Voice("Amelia");

    /** Female, American accent — English. */
    public static final Voice EMILY  = new Voice("Emily");

    /** Male, Gulf accent — Arabic. */
    public static final Voice HAMAD  = new Voice("Hamad");

    /** Male, British accent — English. */
    public static final Voice HARRY  = new Voice("Harry");

    /** Female, Standard — Arabic. */
    public static final Voice HUDA   = new Voice("Huda");

    /** Male, American accent — English. */
    public static final Voice JAKE   = new Voice("Jake");

    /** Male, Gulf accent — Arabic. */
    public static final Voice JASIM  = new Voice("Jasim");

    /** Female, Standard — Arabic. */
    public static final Voice NOOR   = new Voice("Noor");

    /** Snapshot of the SDK's bundled built-in voices. Custom voices created via the API are
     *  outside this set but still valid via {@link #of(String)}. */
    public static final Set<Voice> KNOWN = Set.of(
            AMELIA, EMILY, HAMAD, HARRY, HUDA, JAKE, JASIM, NOOR);

    public Voice {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new Voice(wireValue)}; provided for API symmetry. */
    public static Voice of(String wireValue) {
        return new Voice(wireValue);
    }
}
