package qa.fanar.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookNameTest {

    private static final String FIRST_KNOWN_TITLE = BookName.KNOWN.iterator().next().wireValue();

    @Test
    void holdsValueForKnownTitle() {
        BookName b = BookName.of(FIRST_KNOWN_TITLE);
        assertEquals(FIRST_KNOWN_TITLE, b.wireValue());
    }

    @Test
    void ofIsEquivalentToCanonicalConstructor() {
        assertEquals(new BookName(FIRST_KNOWN_TITLE), BookName.of(FIRST_KNOWN_TITLE));
    }

    @Test
    void ofIsLenientAndAcceptsUnknownTitles() {
        BookName custom = BookName.of("a title not in the bundled corpus");
        assertEquals("a title not in the bundled corpus", custom.wireValue());
        assertFalse(BookName.KNOWN.contains(custom),
                "unknown titles construct successfully but are not in KNOWN");
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new BookName(null));
        assertThrows(NullPointerException.class, () -> BookName.of(null));
    }

    @Test
    void knownContainsBundledCorpus() {
        // Spec lists 572 titles as of api-spec/openapi.json; if this drifts the inline
        // catalogue and the spec are out of sync.
        assertEquals(572, BookName.KNOWN.size());
        assertTrue(BookName.KNOWN.contains(BookName.of(FIRST_KNOWN_TITLE)));
    }

    @Test
    void knownIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> BookName.KNOWN.add(BookName.of("forbidden")));
    }

    @Test
    void knownIsAStableConstantReference() {
        assertSame(BookName.KNOWN, BookName.KNOWN);
    }
}
