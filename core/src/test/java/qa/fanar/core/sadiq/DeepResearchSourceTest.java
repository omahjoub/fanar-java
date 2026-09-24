package qa.fanar.core.sadiq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DeepResearchSourceTest {

    @Test
    void holdsAllFields() {
        DeepResearchSource s = new DeepResearchSource(
                "https://islamweb.net/ar/article/228949/", "[إسلام ويب:228949]", "العِلْمُ نورٌ", true);
        assertEquals("https://islamweb.net/ar/article/228949/", s.source());
        assertEquals("[إسلام ويب:228949]", s.citationTag());
        assertEquals("العِلْمُ نورٌ", s.quote());
        assertEquals(Boolean.TRUE, s.wasCited());
    }

    @Test
    void everyFieldMayBeNull() {
        // The spec types the source arrays as untyped; nothing about an item is guaranteed.
        DeepResearchSource s = new DeepResearchSource(null, null, null, null);
        assertNull(s.source());
        assertNull(s.citationTag());
        assertNull(s.quote());
        assertNull(s.wasCited());
    }
}
