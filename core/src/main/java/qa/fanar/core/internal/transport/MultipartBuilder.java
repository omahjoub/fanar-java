package qa.fanar.core.internal.transport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Minimal {@code multipart/form-data} body builder for the JDK {@code HttpClient}.
 *
 * <p>The JDK has no native helper for multipart bodies. This class hand-constructs one per
 * RFC 7578: each part has {@code Content-Disposition} (and, for files, {@code Content-Type})
 * headers, separated by a generated boundary. Use {@link #contentType()} for the request's
 * {@code Content-Type} header so the boundary stays in sync with the body.</p>
 *
 * <p>Internal (ADR-018). Used by {@code AudioClientImpl} for {@code createVoice} and
 * {@code transcribe}; not part of the public API.</p>
 *
 * @author Oussama Mahjoub
 */
public final class MultipartBuilder {

    private static final String CRLF = "\r\n";

    private final String boundary;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    public MultipartBuilder() {
        this.boundary = "----FanarBoundary" + UUID.randomUUID().toString().replace("-", "");
    }

    /** Value to put in the request's {@code Content-Type} header. */
    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    /** Add a string-valued form field. */
    public MultipartBuilder addField(String name, String value) {
        startPart();
        write("Content-Disposition: form-data; name=\"" + escape(name) + "\"" + CRLF);
        write(CRLF);
        write(value);
        write(CRLF);
        return this;
    }

    /**
     * Add a binary file part.
     *
     * @param name        form field name
     * @param filename    filename to advertise to the server (e.g. {@code "audio.wav"})
     * @param contentType MIME type of the bytes (e.g. {@code "audio/wav"})
     * @param bytes       raw bytes; not copied — caller must not mutate after this call
     */
    public MultipartBuilder addFile(String name, String filename, String contentType, byte[] bytes) {
        startPart();
        write("Content-Disposition: form-data; name=\"" + escape(name)
                + "\"; filename=\"" + escape(filename) + "\"" + CRLF);
        write("Content-Type: " + contentType + CRLF);
        write(CRLF);
        buf.writeBytes(bytes);
        write(CRLF);
        return this;
    }

    /** Close the multipart body and return the assembled bytes. After calling this, the builder is consumed. */
    public byte[] build() {
        write("--" + boundary + "--" + CRLF);
        return buf.toByteArray();
    }

    private void startPart() {
        write("--" + boundary + CRLF);
    }

    private void write(String s) {
        buf.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        // Per RFC 7578 / 5987: quote " and CRLF in field/file names. Most callers won't need this,
        // but it's cheap insurance against breaking the part header.
        return value.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
    }
}
