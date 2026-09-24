package qa.fanar.core.sadiq;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepResearchSectionTest {

    private static final DeepResearchSource SOURCE =
            new DeepResearchSource("https://example.org/a", "[a:1]", "quote", true);

    @Test
    void holdsAllFieldsIncludingNestedChildren() {
        DeepResearchSection leaf = new DeepResearchSection(
                "Evidence", "الأدلة", "## Evidence\n\n...", "general", List.of(SOURCE), List.of(SOURCE), false, null);
        DeepResearchSection root = new DeepResearchSection(
                "Basis", "الأساس", "## Basis", "general", List.of(), List.of(), null, List.of(leaf));

        assertEquals("Basis", root.heading());
        assertEquals("الأساس", root.headingAr());
        assertEquals("## Basis", root.content());
        assertEquals("general", root.mode());
        assertNull(root.degraded());
        assertEquals(1, root.children().size());
        assertEquals("Evidence", root.children().getFirst().heading());
        assertEquals(Boolean.FALSE, root.children().getFirst().degraded());
        assertEquals(List.of(SOURCE), root.children().getFirst().citedSources());
    }

    @Test
    void nullListsBecomeEmptyAndScalarsStayNull() {
        DeepResearchSection s = new DeepResearchSection(null, null, null, null, null, null, null, null);
        assertNull(s.heading());
        assertNull(s.content());
        assertTrue(s.sources().isEmpty());
        assertTrue(s.citedSources().isEmpty());
        assertTrue(s.children().isEmpty());
    }

    @Test
    void listsAreDefensivelyCopiedAndUnmodifiable() {
        List<DeepResearchSource> sources = new ArrayList<>(List.of(SOURCE));
        List<DeepResearchSection> children = new ArrayList<>();
        DeepResearchSection s = new DeepResearchSection("h", null, null, null, sources, sources, null, children);
        sources.clear();
        children.add(s);

        assertEquals(1, s.sources().size());
        assertEquals(1, s.citedSources().size());
        assertTrue(s.children().isEmpty());
        assertNotSame(sources, s.sources());
        assertThrows(UnsupportedOperationException.class, () -> s.children().add(s));
    }
}
