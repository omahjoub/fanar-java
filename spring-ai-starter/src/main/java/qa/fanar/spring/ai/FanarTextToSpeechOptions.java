package qa.fanar.spring.ai;

import org.springframework.ai.audio.tts.TextToSpeechOptions;

import qa.fanar.core.audio.QuranReciter;

/**
 * Fanar-specific {@link TextToSpeechOptions}: the standard portable knobs plus the Fanar
 * parameters portable options cannot carry — emotional synthesis and Quranic reciter selection.
 *
 * <p>Pass an instance on the {@code TextToSpeechPrompt}; {@code FanarTextToSpeechModel} maps the
 * portable getters like any {@link TextToSpeechOptions} and additionally applies the Fanar
 * extras. Any other implementation keeps working — the extras are then simply unset
 * (ADR-024). {@link #getSpeed()} remains unsupported by Fanar's wire format and is dropped.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarTextToSpeechOptions implements TextToSpeechOptions {

    private final String model;
    private final String voice;
    private final String format;
    private final Double speed;
    private final Boolean withEmotion;
    private final QuranReciter quranReciter;

    private FanarTextToSpeechOptions(Builder b) {
        this.model = b.model;
        this.voice = b.voice;
        this.format = b.format;
        this.speed = b.speed;
        this.withEmotion = b.withEmotion;
        this.quranReciter = b.quranReciter;
    }

    /** Start a fresh builder. */
    public static Builder builder() {
        return new Builder();
    }

    @Override public String getModel() { return model; }
    @Override public String getVoice() { return voice; }
    @Override public String getFormat() { return format; }
    @Override public Double getSpeed() { return speed; }

    /**
     * Emotional speech synthesis ({@code Fanar-Aura-TTS-2} + emotion-capable voices only),
     * or {@code null}.
     */
    public Boolean getWithEmotion() { return withEmotion; }

    /** Reciter selection for {@code Fanar-Sadiq-TTS-1}, or {@code null}. */
    public QuranReciter getQuranReciter() { return quranReciter; }

    /** Fluent builder; every field defaults to {@code null} ("use the adapter/server default"). */
    public static final class Builder {

        private String model;
        private String voice;
        private String format;
        private Double speed;
        private Boolean withEmotion;
        private QuranReciter quranReciter;

        private Builder() {
            // use FanarTextToSpeechOptions.builder()
        }

        public Builder model(String model) { this.model = model; return this; }
        public Builder voice(String voice) { this.voice = voice; return this; }
        public Builder format(String format) { this.format = format; return this; }
        public Builder speed(Double speed) { this.speed = speed; return this; }
        public Builder withEmotion(Boolean withEmotion) { this.withEmotion = withEmotion; return this; }
        public Builder quranReciter(QuranReciter quranReciter) { this.quranReciter = quranReciter; return this; }

        public FanarTextToSpeechOptions build() {
            return new FanarTextToSpeechOptions(this);
        }
    }
}
