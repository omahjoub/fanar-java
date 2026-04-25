package qa.fanar.json.jackson2;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import qa.fanar.core.audio.SpeechToTextResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link SpeechToTextResponseDeserializer} — exercises every branch of the
 * variant discriminator (text / srt / json / unknown).
 */
class SpeechToTextResponseDeserializerTest {

    private final Jackson2FanarJsonCodec codec = new Jackson2FanarJsonCodec();

    @Test
    void textVariant() throws IOException {
        SpeechToTextResponse r = decode("{\"id\":\"a\",\"text\":\"hello\"}");
        SpeechToTextResponse.Text t = assertInstanceOf(SpeechToTextResponse.Text.class, r);
        assertEquals("a", t.id());
        assertEquals("hello", t.text());
    }

    @Test
    void srtVariant() throws IOException {
        SpeechToTextResponse r = decode("{\"id\":\"b\",\"srt\":\"1\\n00:00:00,000 --> 00:00:01,000\\nhi\\n\"}");
        SpeechToTextResponse.Srt s = assertInstanceOf(SpeechToTextResponse.Srt.class, r);
        assertEquals("b", s.id());
        assertTrue(s.srt().contains("hi"));
    }

    @Test
    void jsonVariantFlattensSegments() throws IOException {
        SpeechToTextResponse r = decode("{\"id\":\"c\",\"json\":{\"segments\":["
                + "{\"speaker\":\"speaker_0\",\"start_time\":0.0,\"end_time\":1.0,"
                + "\"duration\":1.0,\"text\":\"hi\"},"
                + "{\"speaker\":\"speaker_1\",\"start_time\":1.0,\"end_time\":2.5,"
                + "\"duration\":1.5,\"text\":\"there\"}"
                + "]}}");
        SpeechToTextResponse.Json j = assertInstanceOf(SpeechToTextResponse.Json.class, r);
        assertEquals("c", j.id());
        assertEquals(2, j.segments().size());
        assertEquals("speaker_0", j.segments().getFirst().speaker());
        assertEquals(0.0, j.segments().get(0).startTime());
        assertEquals("hi", j.segments().get(0).text());
        assertEquals(1.5, j.segments().get(1).duration());
    }

    @Test
    void jsonVariantWithEmptySegments() throws IOException {
        SpeechToTextResponse r = decode("{\"id\":\"d\",\"json\":{\"segments\":[]}}");
        SpeechToTextResponse.Json j = assertInstanceOf(SpeechToTextResponse.Json.class, r);
        assertEquals(0, j.segments().size());
    }

    @Test
    void unknownDiscriminatorThrows() {
        IOException ex = assertThrows(IOException.class,
                () -> decode("{\"id\":\"e\",\"unknown_field\":true}"));
        assertTrue(ex.getMessage() != null
                || (ex.getCause() != null && ex.getCause().getMessage() != null),
                "must surface a mismatched-input message");
    }

    private SpeechToTextResponse decode(String json) throws IOException {
        return codec.decode(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                SpeechToTextResponse.class);
    }
}
