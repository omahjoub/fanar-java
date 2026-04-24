package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResponseContentTest {

    @Test
    void textContentHoldsText() {
        assertEquals("hello", new TextContent("hello").text());
    }

    @Test
    void textContentRejectsNull() {
        assertThrows(NullPointerException.class, () -> new TextContent(null));
    }

    @Test
    void imageContentHoldsUrl() {
        assertEquals("https://example.com/a.png", new ImageContent("https://example.com/a.png").url());
    }

    @Test
    void imageContentRejectsNull() {
        assertThrows(NullPointerException.class, () -> new ImageContent(null));
    }

    @Test
    void audioContentHoldsUrl() {
        assertEquals("https://example.com/a.mp3", new AudioContent("https://example.com/a.mp3").url());
    }

    @Test
    void audioContentRejectsNull() {
        assertThrows(NullPointerException.class, () -> new AudioContent(null));
    }

    @Test
    void responseContentHierarchyIsExhaustive() {
        ResponseContent part = new TextContent("x");
        String kind = switch (part) {
            case TextContent  t -> "text";
            case ImageContent i -> "image";
            case AudioContent a -> "audio";
        };
        assertEquals("text", kind);
    }
}
