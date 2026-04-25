package qa.fanar.core.internal.transport;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartBuilderTest {

    @Test
    void contentTypeCarriesGeneratedBoundary() {
        MultipartBuilder mb = new MultipartBuilder();
        String ct = mb.contentType();
        assertTrue(ct.startsWith("multipart/form-data; boundary=----FanarBoundary"),
                "unexpected content-type: " + ct);
    }

    @Test
    void buildAssemblesFieldsAndFilesWithCrlfBoundaries() {
        MultipartBuilder mb = new MultipartBuilder();
        mb.addField("name", "alice");
        mb.addFile("audio", "audio.wav", "audio/wav", new byte[]{1, 2, 3});
        mb.addField("transcript", "hello");

        String body = new String(mb.build(), StandardCharsets.UTF_8);
        String boundary = mb.contentType().substring(mb.contentType().indexOf("boundary=") + 9);

        assertTrue(body.startsWith("--" + boundary + "\r\n"), "body must start with boundary marker: " + body);
        assertTrue(body.contains("Content-Disposition: form-data; name=\"name\"\r\n\r\nalice\r\n"),
                "name part shape: " + body);
        assertTrue(body.contains("Content-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"\r\n"
                        + "Content-Type: audio/wav\r\n\r\n"),
                "audio part headers: " + body);
        assertTrue(body.contains("Content-Disposition: form-data; name=\"transcript\"\r\n\r\nhello\r\n"),
                "transcript part shape: " + body);
        assertTrue(body.endsWith("--" + boundary + "--\r\n"),
                "body must end with closing boundary marker: " + body);
    }

    @Test
    void escapesQuoteAndCrlfInFieldNames() {
        MultipartBuilder mb = new MultipartBuilder();
        mb.addField("a\"b\rc\nd", "value");
        String body = new String(mb.build(), StandardCharsets.UTF_8);
        assertTrue(body.contains("name=\"a%22b%0Dc%0Ad\""),
                "quotes and CRLF must be percent-escaped in field names: " + body);
    }

    @Test
    void escapesSameInFilename() {
        MultipartBuilder mb = new MultipartBuilder();
        mb.addFile("audio", "a\"b.wav", "audio/wav", new byte[]{0});
        String body = new String(mb.build(), StandardCharsets.UTF_8);
        assertTrue(body.contains("filename=\"a%22b.wav\""), body);
    }

    @Test
    void emptyBuilderProducesOnlyClosingBoundary() {
        MultipartBuilder mb = new MultipartBuilder();
        String body = new String(mb.build(), StandardCharsets.UTF_8);
        String boundary = mb.contentType().substring(mb.contentType().indexOf("boundary=") + 9);
        assertEquals("--" + boundary + "--\r\n", body);
    }

}
