package qa.fanar.core.audio;

import java.util.Arrays;
import java.util.Objects;

/**
 * Request to transcribe audio via {@code POST /v1/audio/transcriptions}.
 *
 * <p>Sent as {@code multipart/form-data} with three (or four) parts: the audio binary plus
 * scalar fields for the model and optional output format. The SDK adapter constructs the
 * multipart body from this record; callers do not interact with the wire shape directly.</p>
 *
 * <p>Equality is content-based ({@code Arrays.equals} on {@code file}); the {@code file}
 * array is <em>not</em> defensively copied — callers must not mutate it for the lifetime of
 * the request.</p>
 *
 * @param file        raw audio bytes; must not be {@code null}
 * @param filename    filename advertised to the server in the multipart {@code Content-Disposition}
 *                    (e.g. {@code "audio.wav"}); must not be {@code null}
 * @param contentType MIME type of the audio (e.g. {@code "audio/wav"}, {@code "audio/mpeg"});
 *                    must not be {@code null}
 * @param model       the STT model to use; must not be {@code null}
 * @param format      output format ({@link SttFormat#TEXT}, {@link SttFormat#SRT},
 *                    {@link SttFormat#JSON}); {@code null} → server default ({@code text})
 */
public record TranscriptionRequest(
        byte[] file,
        String filename,
        String contentType,
        SttModel model,
        SttFormat format
) {

    public TranscriptionRequest {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(model, "model");
        // format nullable — server default applies
    }

    /** Static factory for the common path: server-default format. */
    public static TranscriptionRequest of(byte[] file, String filename, String contentType, SttModel model) {
        return new TranscriptionRequest(file, filename, contentType, model, null);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof TranscriptionRequest(
            byte[] file1, String filename1, String type, SttModel model1, SttFormat format1
        )
                && Arrays.equals(file, file1)
                && filename.equals(filename1)
                && contentType.equals(type)
                && model.equals(model1)
                && Objects.equals(format, format1);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(filename, contentType, model, format) + Arrays.hashCode(file);
    }

    @Override
    public String toString() {
        return "TranscriptionRequest[file=<" + file.length + " bytes>"
                + ", filename=" + filename
                + ", contentType=" + contentType
                + ", model=" + model
                + ", format=" + format + "]";
    }
}
