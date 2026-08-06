package qa.fanar.spring.ai;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.audio.tts.Speech;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechOptions;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import qa.fanar.core.FanarClient;
import qa.fanar.core.audio.TextToSpeechRequest;
import qa.fanar.core.audio.TtsModel;
import qa.fanar.core.audio.TtsResponseFormat;
import qa.fanar.core.audio.Voice;

/**
 * Spring AI {@link TextToSpeechModel} adapter backed by Fanar's {@code POST /v1/audio/speech}.
 *
 * <p>Maps Spring AI's {@link TextToSpeechPrompt} + {@link TextToSpeechOptions} onto a Fanar
 * {@link TextToSpeechRequest} and returns the raw audio bytes wrapped as a {@link Speech}.</p>
 *
 * <p>Streaming: {@link #stream(TextToSpeechPrompt)} uses Fanar's chunked delivery
 * ({@code stream:true} on the wire) via {@code AudioClient.speechStream(...)} and emits one
 * {@link TextToSpeechResponse} per audio chunk as the server generates it. Chunk boundaries
 * follow transport reads — concatenate the chunks' bytes in emission order to reconstruct the
 * full clip.</p>
 *
 * <p>Spring AI's {@link TextToSpeechOptions#getSpeed()} is silently dropped — Fanar's wire format
 * has no playback-speed parameter. Callers can resample client-side after receiving the bytes.
 * Pass a {@link FanarTextToSpeechOptions} to reach the Fanar-only knobs (emotional synthesis,
 * Quranic reciter) — see ADR-024.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarTextToSpeechModel implements TextToSpeechModel {

    private final FanarClient fanar;
    private final TtsModel defaultModel;
    private final Voice defaultVoice;

    /**
     * Construct an adapter with default model + voice. The defaults take effect when the
     * incoming {@link TextToSpeechPrompt} omits options or sets blank values.
     *
     * @param fanar        the auto-wired SDK client
     * @param defaultModel typed Fanar TTS model (e.g. {@code TtsModel.FANAR_AURA_TTS_2})
     * @param defaultVoice typed Fanar voice (e.g. {@code Voice.AMELIA})
     */
    public FanarTextToSpeechModel(FanarClient fanar, TtsModel defaultModel, Voice defaultVoice) {
        this.fanar = Objects.requireNonNull(fanar, "fanar");
        this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel");
        this.defaultVoice = Objects.requireNonNull(defaultVoice, "defaultVoice");
    }

    @Override
    public TextToSpeechResponse call(TextToSpeechPrompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        TextToSpeechRequest request = toFanarRequest(prompt);
        byte[] audio = fanar.audio().speech(request);
        return new TextToSpeechResponse(List.of(new Speech(audio)));
    }

    @Override
    public Flux<TextToSpeechResponse> stream(TextToSpeechPrompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        return Flux.defer(() -> JdkFlowAdapter.flowPublisherToFlux(
                        fanar.audio().speechStream(toFanarRequest(prompt)))
                .map(chunk -> new TextToSpeechResponse(List.of(new Speech(chunk)))));
    }

    private TextToSpeechRequest toFanarRequest(TextToSpeechPrompt prompt) {
        TextToSpeechOptions options = prompt.getOptions();
        TextToSpeechRequest.Builder builder = TextToSpeechRequest.builder()
                .model(resolveModel(options))
                .input(prompt.getInstructions().getText())
                .voice(resolveVoice(options))
                .responseFormat(resolveFormat(options));
        if (options instanceof FanarTextToSpeechOptions fanarOptions) {
            // Fanar extras beyond the portable TextToSpeechOptions surface (ADR-024).
            builder.withEmotion(fanarOptions.getWithEmotion());
            builder.quranReciter(fanarOptions.getQuranReciter());
        }
        return builder.build();
    }

    private TtsModel resolveModel(TextToSpeechOptions options) {
        if (options != null && options.getModel() != null && !options.getModel().isBlank()) {
            return TtsModel.of(options.getModel());
        }
        return defaultModel;
    }

    private Voice resolveVoice(TextToSpeechOptions options) {
        if (options != null && options.getVoice() != null && !options.getVoice().isBlank()) {
            return new Voice(options.getVoice());
        }
        return defaultVoice;
    }

    private static TtsResponseFormat resolveFormat(TextToSpeechOptions options) {
        // Null format means "let the server pick its default" — Fanar defaults to mp3.
        if (options == null || options.getFormat() == null || options.getFormat().isBlank()) {
            return null;
        }
        return TtsResponseFormat.of(options.getFormat());
    }
}
