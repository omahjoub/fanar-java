package qa.fanar.sample.spring.ai;

import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * REST controller exercising the image / TTS / STT model beans the starter registers. These
 * skip Spring AI's higher-level fluent surface (no equivalent of {@link
 * org.springframework.ai.chat.client.ChatClient} for the audio/image domains in 2.0.0-M4) and
 * call the model beans directly.
 *
 * @author Oussama Mahjoub
 */
@RestController
@RequestMapping("/api")
public class MediaController {

    private final ImageModel image;
    private final TextToSpeechModel tts;
    private final TranscriptionModel stt;

    public MediaController(ImageModel image, TextToSpeechModel tts, TranscriptionModel stt) {
        this.image = image;
        this.tts = tts;
        this.stt = stt;
    }

    /** Generate an image and return its base64-encoded bytes. */
    @PostMapping("/image")
    public ImageReply image(@RequestBody ImagePromptBody body) {
        ImageResponse response = image.call(new ImagePrompt(body.prompt()));
        return new ImageReply(response.getResult().getOutput().getB64Json());
    }

    /**
     * Synthesize speech and stream the bytes back as {@code audio/mpeg}. Browsers can render
     * this as an inline audio element; {@code curl --output speech.mp3} works too.
     */
    @PostMapping(value = "/speak", produces = "audio/mpeg")
    public ResponseEntity<byte[]> speak(@RequestBody SpeakBody body) {
        byte[] audio = tts.call(new TextToSpeechPrompt(body.text())).getResult().getOutput();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"speech.mp3\"")
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .body(audio);
    }

    /**
     * Transcribe an uploaded audio file. Curl: {@code curl -F audio=@clip.wav
     * http://localhost:8080/api/transcribe}.
     */
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TranscriptReply transcribe(@RequestParam("audio") MultipartFile audio) throws IOException {
        Resource resource = new ByteArrayResource(audio.getBytes()) {
            @Override public String getFilename() { return audio.getOriginalFilename(); }
        };
        String text = stt.call(new AudioTranscriptionPrompt(resource)).getResult().getOutput();
        return new TranscriptReply(text);
    }

    /** Health probe alternative: confirms the controller is up without hitting Fanar. */
    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    public record ImagePromptBody(String prompt) { }
    public record ImageReply(String b64Json) { }
    public record SpeakBody(String text) { }
    public record TranscriptReply(String text) { }
}
