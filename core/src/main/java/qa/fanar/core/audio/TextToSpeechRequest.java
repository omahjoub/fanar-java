package qa.fanar.core.audio;

import java.util.Objects;

/**
 * Request to synthesize speech via {@code POST /v1/audio/speech}.
 *
 * <p>The {@code voice} field is typed {@link Voice} so callers can use either built-in voices
 * ({@link Voice#KNOWN}) or personalized voices created via {@code AudioClient.createVoice(...)}.
 * The {@code quranReciter} field is only meaningful with {@link TtsModel#FANAR_SADIQ_TTS_1};
 * the server applies its default ({@link QuranReciter#ABDUL_BASIT}) when omitted with that
 * model and ignores the field for {@link TtsModel#FANAR_AURA_TTS_2}.</p>
 *
 * @param model          the TTS model to use; must not be {@code null}
 * @param input          the text to synthesize; must not be {@code null}
 * @param voice          which voice to use; must not be {@code null}
 * @param responseFormat audio container ({@link TtsResponseFormat#MP3} or
 *                       {@link TtsResponseFormat#WAV}); {@code null} → server default (mp3)
 * @param quranReciter   reciter selection for the Sadiq TTS model; {@code null} → server default
 *
 * @author Oussama Mahjoub
 */
public record TextToSpeechRequest(
        TtsModel model,
        String input,
        Voice voice,
        TtsResponseFormat responseFormat,
        QuranReciter quranReciter
) {

    public TextToSpeechRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(voice, "voice");
        // responseFormat + quranReciter nullable — server applies its defaults
    }

    /** Static factory for the common path: model + text + voice, server-default format/reciter. */
    public static TextToSpeechRequest of(TtsModel model, String input, Voice voice) {
        return new TextToSpeechRequest(model, input, voice, null, null);
    }
}
