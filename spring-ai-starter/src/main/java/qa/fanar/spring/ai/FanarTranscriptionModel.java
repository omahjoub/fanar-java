package qa.fanar.spring.ai;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.springframework.ai.audio.transcription.AudioTranscription;
import org.springframework.ai.audio.transcription.AudioTranscriptionOptions;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.core.io.Resource;

import qa.fanar.core.FanarClient;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.audio.SpeechToTextResponse;
import qa.fanar.core.audio.SttModel;
import qa.fanar.core.audio.TranscriptionRequest;

/**
 * Spring AI {@link TranscriptionModel} adapter backed by Fanar's
 * {@code POST /v1/audio/transcriptions}.
 *
 * <p>Reads bytes from the Spring {@link Resource} carried by {@link AudioTranscriptionPrompt},
 * sends them to Fanar as multipart, and projects the {@link SpeechToTextResponse.Text} into
 * Spring AI's {@link AudioTranscription}. Always requests the {@code text} format — Spring AI's
 * transcription contract returns a single string output, so {@code srt} and {@code json}
 * variants would lose information through this surface.</p>
 *
 * <p>Output format selection is therefore <em>not</em> exposed via {@link AudioTranscriptionOptions}.
 * Callers who need SRT or time-aligned segments should use {@code FanarClient.audio().transcribe(...)}
 * directly instead of going through Spring AI.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarTranscriptionModel implements TranscriptionModel {

    private final FanarClient fanar;
    private final SttModel defaultModel;

    /**
     * Construct an adapter with a default Fanar STT model. The default applies when the
     * {@link AudioTranscriptionPrompt} omits {@link AudioTranscriptionOptions#getModel()} or
     * sets a blank value.
     *
     * @param fanar        the auto-wired SDK client
     * @param defaultModel typed Fanar STT model (e.g. {@code SttModel.FANAR_AURA_STT_1})
     */
    public FanarTranscriptionModel(FanarClient fanar, SttModel defaultModel) {
        this.fanar = Objects.requireNonNull(fanar, "fanar");
        this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel");
    }

    @Override
    public AudioTranscriptionResponse call(AudioTranscriptionPrompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        Resource resource = prompt.getInstructions();
        TranscriptionRequest request = TranscriptionRequest.of(
                readAllBytes(resource),
                filenameOf(resource),
                contentTypeOf(resource),
                resolveModel(prompt.getOptions()));
        SpeechToTextResponse fanarResponse = fanar.audio().transcribe(request);
        // We always request format=text (TranscriptionRequest.of leaves format null → server
        // default text); the response is therefore always a Text variant.
        SpeechToTextResponse.Text text = (SpeechToTextResponse.Text) fanarResponse;
        return new AudioTranscriptionResponse(new AudioTranscription(text.text()));
    }

    private SttModel resolveModel(AudioTranscriptionOptions options) {
        if (options != null && options.getModel() != null && !options.getModel().isBlank()) {
            return SttModel.of(options.getModel());
        }
        return defaultModel;
    }

    private static byte[] readAllBytes(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new FanarTransportException("Failed to read audio resource: " + resource, e);
        }
    }

    private static String filenameOf(Resource resource) {
        String filename = resource.getFilename();
        return filename != null ? filename : "audio";
    }

    private static String contentTypeOf(Resource resource) {
        // Resource doesn't carry a media type; Fanar requires `Content-Type` for the audio part
        // of the multipart body. Default to `application/octet-stream` — the server sniffs the
        // bytes regardless. Callers who need a precise mime can use FanarClient.audio() directly.
        String name = resource.getFilename();
        if (name == null) {
            return "application/octet-stream";
        }
        String lower = name.toLowerCase();
        if (lower.endsWith(".wav"))  return "audio/wav";
        if (lower.endsWith(".mp3"))  return "audio/mpeg";
        if (lower.endsWith(".m4a"))  return "audio/mp4";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".ogg"))  return "audio/ogg";
        return "application/octet-stream";
    }
}
