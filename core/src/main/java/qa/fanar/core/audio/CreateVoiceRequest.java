package qa.fanar.core.audio;

import java.util.Arrays;
import java.util.Objects;

/**
 * Request to create a personalized voice via {@code POST /v1/audio/voices} — uploads a WAV
 * sample plus its transcript so Fanar can clone the speaker.
 *
 * <p>The wire format is {@code multipart/form-data} with three fields: {@code name},
 * {@code audio} (binary), and {@code transcript}. The SDK adapter turns this record into a
 * multipart body; callers do not interact with the wire shape directly.</p>
 *
 * <p>Equality is content-based ({@code Arrays.equals} on {@code audio}) rather than identity-based.
 * The {@code audio} array is <em>not</em> defensively copied — callers must not mutate it for
 * the lifetime of the request.</p>
 *
 * @param name       the personalized voice name; must not be {@code null}
 * @param audio      WAV bytes for the speaker sample; must not be {@code null}
 * @param transcript the exact transcription of the audio; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record CreateVoiceRequest(String name, byte[] audio, String transcript) {

    public CreateVoiceRequest {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(audio, "audio");
        Objects.requireNonNull(transcript, "transcript");
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof CreateVoiceRequest(String name1, byte[] audio1, String transcript1)
                && name.equals(name1)
                && Arrays.equals(audio, audio1)
                && transcript.equals(transcript1);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(name, transcript) + Arrays.hashCode(audio);
    }

    @Override
    public String toString() {
        return "CreateVoiceRequest[name=" + name
                + ", audio=<" + audio.length + " bytes>"
                + ", transcript=" + transcript + "]";
    }
}
