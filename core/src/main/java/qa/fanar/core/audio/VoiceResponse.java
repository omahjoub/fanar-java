package qa.fanar.core.audio;

import java.util.List;
import java.util.Objects;

/**
 * Response from {@code GET /v1/audio/voices}.
 *
 * <p>The listing always includes the built-in public voices and additionally the personalized
 * voices registered for this API key when voice personalization is authorized. Each entry is a
 * rich {@link AvailableVoice}; its {@link AvailableVoice#name() name} is the identifier accepted
 * by {@code TextToSpeechRequest.voice()}.</p>
 *
 * @param voices the available voices, defensively copied and unmodifiable
 *
 * @author Oussama Mahjoub
 */
public record VoiceResponse(List<AvailableVoice> voices) {

    public VoiceResponse {
        Objects.requireNonNull(voices, "voices");
        voices = List.copyOf(voices);
    }
}
