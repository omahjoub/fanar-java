package qa.fanar.core.chat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookNameTest {

    private static final String FIRST_KNOWN_TITLE = BookName.known().iterator().next();

    @Test
    void holdsValueForKnownTitle() {
        BookName b = BookName.of(FIRST_KNOWN_TITLE);
        assertEquals(FIRST_KNOWN_TITLE, b.value());
    }

    @Test
    void ofIsEquivalentToCanonicalConstructor() {
        assertEquals(new BookName(FIRST_KNOWN_TITLE), BookName.of(FIRST_KNOWN_TITLE));
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new BookName(null));
    }

    @Test
    void rejectsUnknownTitle() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BookName.of("not a real Sadiq title"));
        assertTrue(e.getMessage().contains("Unknown Sadiq book title"), e.getMessage());
    }

    @Test
    void isKnownReturnsTrueForKnown() {
        assertTrue(BookName.isKnown(FIRST_KNOWN_TITLE));
    }

    @Test
    void isKnownReturnsFalseForUnknown() {
        assertFalse(BookName.isKnown("definitely not in the spec"));
    }

    @Test
    void knownReturnsSpecCorpus() {
        Set<String> all = BookName.known();
        assertNotNull(all);
        // Spec lists 572 titles as of api-spec/openapi.json; if this drifts the resource
        // and the spec are out of sync.
        assertEquals(572, all.size());
        assertTrue(all.contains(FIRST_KNOWN_TITLE));
    }

    @Test
    void knownIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> BookName.known().add("forbidden"));
    }

    @Test
    void knownReturnsSameInstanceAcrossCalls() {
        assertSame(BookName.known(), BookName.known());
    }

    // --- parse() — package-private for direct branch coverage of the loader

    @Test
    void parseSkipsBlankLinesAndPreservesOrder() {
        Set<String> parsed = BookName.parse(stringStream("one\n\ntwo\nthree\n"));
        assertEquals(3, parsed.size());
        assertEquals("one", parsed.iterator().next());
    }

    @Test
    void parseThrowsWhenStreamIsNull() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BookName.parse(null));
        assertTrue(e.getMessage().contains("book-names.txt missing"), e.getMessage());
    }

    @Test
    void parseThrowsWhenStreamReadFails() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }
        };
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> BookName.parse(broken));
        assertTrue(e.getMessage().contains("Failed to load book-names"), e.getMessage());
    }

    @Test
    void parseReturnsUnmodifiableSet() {
        Set<String> parsed = BookName.parse(stringStream("a\n"));
        assertThrows(UnsupportedOperationException.class, () -> parsed.add("b"));
    }

    private static InputStream stringStream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
