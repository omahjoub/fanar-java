package qa.fanar.core.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A typed reference to one of the books in Fanar's {@code BookNamesEnum} corpus — used by the
 * {@code Fanar-Sadiq} Islamic-RAG endpoint to constrain retrieval to specific titles.
 *
 * <p>The OpenAPI {@code BookNamesEnum} schema defines 572 specific Arabic titles (no Latin
 * transliteration, no ASCII identifiers). Hand-mapping them to Java enum constants would be
 * lossy and unreadable, so this type is a thin validating wrapper instead: construct via
 * {@link #of(String)} with a literal spec value, get a runtime guarantee that the title is
 * known before the request hits the server (avoiding the otherwise-opaque 422 response that
 * Fanar emits for unknown values).</p>
 *
 * <p>The set of known titles is loaded once from the {@code book-names.txt} resource that lives
 * alongside this class. Keep that file synchronised with {@code api-spec/openapi.json} when the
 * spec ships an updated corpus.</p>
 *
 * @param value the exact wire string as it appears in the {@code BookNamesEnum} spec
 */
public record BookName(String value) {

    private static final Set<String> KNOWN = parse(
            BookName.class.getResourceAsStream("book-names.txt"));

    public BookName {
        Objects.requireNonNull(value, "value");
        if (!KNOWN.contains(value)) {
            throw new IllegalArgumentException(
                    "Unknown Sadiq book title: \"" + value + "\". "
                            + "Must be one of " + KNOWN.size()
                            + " values from BookNamesEnum (see BookName.known()).");
        }
    }

    /** Equivalent to {@code new BookName(value)}; provided for API symmetry with other types. */
    public static BookName of(String value) {
        return new BookName(value);
    }

    /** {@code true} if {@code title} is a recognised entry of {@code BookNamesEnum}. */
    public static boolean isKnown(String title) {
        return KNOWN.contains(title);
    }

    /**
     * The full set of recognised titles, in the order they appear in the spec. Useful for
     * UIs or tooling that needs to render the catalogue. Returned view is unmodifiable.
     */
    public static Set<String> known() {
        return KNOWN;
    }

    /**
     * Parse one title per non-empty line from the supplied UTF-8 stream. Package-private so
     * tests can drive the null-stream and read-failure branches without needing a custom
     * classloader. Production callers go through the static initializer above, which always
     * passes the bundled resource.
     */
    static Set<String> parse(InputStream in) {
        if (in == null) {
            throw new IllegalStateException(
                    "Resource book-names.txt missing from qa.fanar.core.chat — "
                            + "the SDK build is broken");
        }
        Set<String> set = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    set.add(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load book-names", e);
        }
        return Collections.unmodifiableSet(set);
    }
}
