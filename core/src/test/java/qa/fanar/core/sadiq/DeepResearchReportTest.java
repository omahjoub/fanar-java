package qa.fanar.core.sadiq;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepResearchReportTest {

    private static final DeepResearchSource SOURCE =
            new DeepResearchSource("https://example.org/a", "[a:1]", "quote", true);
    private static final DeepResearchSection SECTION =
            new DeepResearchSection("Basis", "الأساس", "## Basis", "general", List.of(SOURCE), List.of(), null, null);

    @Test
    void holdsAllFields() {
        DeepResearchReport r = new DeepResearchReport(
                "topic", "ar", "Title", "العنوان", "overview", List.of(SECTION), List.of(SOURCE),
                Map.of("depth", "quick", "elapsed_seconds", 267.32), "# Title\n\n...");
        assertEquals("topic", r.topic());
        assertEquals("ar", r.language());
        assertEquals("Title", r.title());
        assertEquals("العنوان", r.titleAr());
        assertEquals("overview", r.overview());
        assertEquals(List.of(SECTION), r.sections());
        assertEquals(List.of(SOURCE), r.sources());
        assertEquals("quick", r.metadata().get("depth"));
        assertEquals(267.32, r.metadata().get("elapsed_seconds"));
        assertEquals("# Title\n\n...", r.markdown());
    }

    @Test
    void nothingIsRequired() {
        // The spec declares no required field on the report; a bare {} must decode.
        DeepResearchReport r = new DeepResearchReport(null, null, null, null, null, null, null, null, null);
        assertNull(r.title());
        assertNull(r.markdown());
        assertTrue(r.sections().isEmpty());
        assertTrue(r.sources().isEmpty());
        assertTrue(r.metadata().isEmpty());
    }

    @Test
    void metadataKeepsNullValuesAndIsUnmodifiable() {
        Map<String, Object> src = new HashMap<>();
        src.put("web_sources_count", null);
        src.put("depth", "standard");
        DeepResearchReport r = new DeepResearchReport(null, null, null, null, null, null, null, src, null);
        src.put("later", 1);

        assertEquals(2, r.metadata().size());
        assertTrue(r.metadata().containsKey("web_sources_count"), "a null value is a value, not an absence");
        assertNull(r.metadata().get("web_sources_count"));
        assertThrows(UnsupportedOperationException.class, () -> r.metadata().put("x", 1));
    }

    @Test
    void listsAreDefensivelyCopiedAndUnmodifiable() {
        List<DeepResearchSection> sections = new ArrayList<>(List.of(SECTION));
        DeepResearchReport r = new DeepResearchReport(null, null, null, null, null, sections, null, null, null);
        sections.clear();
        assertEquals(1, r.sections().size());
        assertThrows(UnsupportedOperationException.class, () -> r.sections().add(SECTION));
        assertThrows(UnsupportedOperationException.class, () -> r.sources().add(SOURCE));
    }
}
