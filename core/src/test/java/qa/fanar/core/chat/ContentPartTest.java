package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentPartTest {

    // --- TextPart

    @Test
    void textPartHoldsText() {
        assertEquals("hi", new TextPart("hi").text());
    }

    @Test
    void textPartRejectsNull() {
        assertThrows(NullPointerException.class, () -> new TextPart(null));
    }

    @Test
    void textPartImplementsBothHierarchies() {
        TextPart tp = new TextPart("x");
        UserContentPart asUser = tp;
        AssistantContentPart asAssistant = tp;
        assertNotNull(asUser);
        assertNotNull(asAssistant);
    }

    // --- ImagePart

    @Test
    void imagePartOfFactory() {
        ImagePart p = ImagePart.of("https://example.com/a.png");
        assertEquals("https://example.com/a.png", p.url());
        assertNull(p.detail());
    }

    @Test
    void imagePartWithDetail() {
        ImagePart p = new ImagePart("https://example.com/a.png", ImageDetail.HIGH);
        assertEquals(ImageDetail.HIGH, p.detail());
    }

    @Test
    void imagePartRejectsNullUrl() {
        assertThrows(NullPointerException.class, () -> new ImagePart(null, ImageDetail.AUTO));
    }

    // --- VideoPart

    @Test
    void videoPartHoldsUrl() {
        assertEquals("https://example.com/v.mp4", new VideoPart("https://example.com/v.mp4").url());
    }

    @Test
    void videoPartRejectsNullUrl() {
        assertThrows(NullPointerException.class, () -> new VideoPart(null));
    }

    // --- RefusalPart

    @Test
    void refusalPartHoldsText() {
        assertEquals("I can't help with that", new RefusalPart("I can't help with that").refusal());
    }

    @Test
    void refusalPartRejectsNull() {
        assertThrows(NullPointerException.class, () -> new RefusalPart(null));
    }

    // --- sealed hierarchies exhaustive

    @Test
    void userContentPartHierarchyIsExhaustive() {
        UserContentPart part = new TextPart("x");
        String kind = switch (part) {
            case TextPart t  -> "text";
            case ImagePart i -> "image";
            case VideoPart v -> "video";
        };
        assertEquals("text", kind);
    }

    @Test
    void assistantContentPartHierarchyIsExhaustive() {
        AssistantContentPart part = new RefusalPart("no");
        String kind = switch (part) {
            case TextPart t    -> "text";
            case RefusalPart r -> "refusal";
        };
        assertEquals("refusal", kind);
    }
}
