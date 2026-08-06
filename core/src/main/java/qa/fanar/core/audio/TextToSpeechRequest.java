package qa.fanar.core.audio;

import java.util.Objects;

/**
 * Request to synthesize speech via {@code POST /v1/audio/speech}.
 *
 * <p>The {@code voice} field is typed {@link Voice} so callers can use either built-in voices
 * ({@link Voice#KNOWN}) or personalized voices created via {@code AudioClient.createVoice(...)}.
 * The {@code quranReciter} field is only meaningful with {@link TtsModel#FANAR_SADIQ_TTS_1};
 * the server applies its default ({@link QuranReciter#ABDUL_BASIT}) when omitted with that
 * model and ignores the field for {@link TtsModel#FANAR_AURA_TTS_2}. The {@code withEmotion}
 * flag only applies to {@link TtsModel#FANAR_AURA_TTS_2} together with a voice whose
 * {@code AvailableVoice.emotion()} is {@code true}; other combinations are rejected server-side
 * with HTTP 422 (ADR-015: model-specific rules are Fanar's responsibility).</p>
 *
 * <p>The wire field {@code stream} is <em>not</em> modelled here. Buffered vs. streamed delivery
 * is a call-site choice on the audio facade ({@code speech(request)} vs.
 * {@code speechStream(request)}), and the transport sets the wire field accordingly.</p>
 *
 * @param model          the TTS model to use; must not be {@code null}
 * @param input          the text to synthesize; must not be {@code null}
 * @param voice          which voice to use; must not be {@code null}
 * @param responseFormat audio container ({@link TtsResponseFormat#MP3} or
 *                       {@link TtsResponseFormat#WAV}); {@code null} → server default (mp3)
 * @param quranReciter   reciter selection for the Sadiq TTS model; {@code null} → server default
 * @param withEmotion    enable emotional speech synthesis; {@code null} → server default (off)
 *
 * @author Oussama Mahjoub
 */
public record TextToSpeechRequest(
        TtsModel model,
        String input,
        Voice voice,
        TtsResponseFormat responseFormat,
        QuranReciter quranReciter,
        Boolean withEmotion
) {

    public TextToSpeechRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(voice, "voice");
        // responseFormat + quranReciter + withEmotion nullable — server applies its defaults
    }

    /** Static factory for the common path: model + text + voice, server defaults elsewhere. */
    public static TextToSpeechRequest of(TtsModel model, String input, Voice voice) {
        return new TextToSpeechRequest(model, input, voice, null, null, null);
    }

    /** Start a fresh builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link TextToSpeechRequest}. Every optional field defaults to
     * {@code null}, meaning "use the server default".
     *
     * <p>{@link #build()} delegates to the record's canonical constructor and therefore runs
     * the same validation — missing required fields throw at {@link #build()}, never later.</p>
     */
    public static final class Builder {

        private TtsModel model;
        private String input;
        private Voice voice;
        private TtsResponseFormat responseFormat;
        private QuranReciter quranReciter;
        private Boolean withEmotion;

        private Builder() {
            // use TextToSpeechRequest.builder()
        }

        public Builder model(TtsModel model) { this.model = model; return this; }
        public Builder input(String input) { this.input = input; return this; }
        public Builder voice(Voice voice) { this.voice = voice; return this; }
        public Builder responseFormat(TtsResponseFormat responseFormat) { this.responseFormat = responseFormat; return this; }
        public Builder quranReciter(QuranReciter quranReciter) { this.quranReciter = quranReciter; return this; }
        public Builder withEmotion(Boolean withEmotion) { this.withEmotion = withEmotion; return this; }

        /**
         * Validate and build the {@link TextToSpeechRequest}.
         *
         * @throws NullPointerException if a required field is missing
         */
        public TextToSpeechRequest build() {
            return new TextToSpeechRequest(model, input, voice, responseFormat, quranReciter, withEmotion);
        }
    }
}
