package qa.fanar.core.audio;

import java.util.List;
import java.util.Objects;

/**
 * Response from {@code GET /v1/audio/voices} — the personalized voices created for the API key.
 *
 * <p>The list contains voice names suitable for use in {@code TextToSpeechRequest.voice()}.
 * The 8 built-in voices ({@link Voice#KNOWN}) are <em>not</em> included; this list is solely
 * the user-created voices.</p>
 *
 * @param voices voice names, defensively copied and unmodifiable
 */
public record VoiceResponse(List<String> voices) {

    public VoiceResponse {
        Objects.requireNonNull(voices, "voices");
        voices = List.copyOf(voices);
    }
}
