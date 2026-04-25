package qa.fanar.core.translations;

import java.util.Objects;
import java.util.Set;

/**
 * Pre-translation text-handling mode. Mirrors the {@code TranslationPreprocessing} schema in
 * the OpenAPI spec, but open: callers can target a new mode via {@link #of(String)} the day
 * Fanar ships it.
 *
 * <p>Modes (per spec):</p>
 * <ul>
 *   <li>{@link #DEFAULT} — split sentences on natural punctuation, trim whitespace, strip HTML.</li>
 *   <li>{@link #PRESERVE_HTML} — same as default but tries to keep HTML tags intact.</li>
 *   <li>{@link #PRESERVE_WHITESPACE} — keeps leading/trailing whitespace and joins sentences
 *       across newlines (useful for fixed-width content).</li>
 *   <li>{@link #PRESERVE_WHITESPACE_AND_HTML} — combines the previous two.</li>
 * </ul>
 *
 * @param wireValue the exact string Fanar accepts on the wire
 */
public record TranslationPreprocessing(String wireValue) {

    public static final TranslationPreprocessing DEFAULT             = new TranslationPreprocessing("default");
    public static final TranslationPreprocessing PRESERVE_HTML       = new TranslationPreprocessing("preserve_html");
    public static final TranslationPreprocessing PRESERVE_WHITESPACE = new TranslationPreprocessing("preserve_whitespace");
    public static final TranslationPreprocessing PRESERVE_WHITESPACE_AND_HTML =
            new TranslationPreprocessing("preserve_whitespace_and_html");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<TranslationPreprocessing> KNOWN = Set.of(
            DEFAULT, PRESERVE_HTML, PRESERVE_WHITESPACE, PRESERVE_WHITESPACE_AND_HTML);

    public TranslationPreprocessing {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new TranslationPreprocessing(wireValue)}; provided for API symmetry. */
    public static TranslationPreprocessing of(String wireValue) {
        return new TranslationPreprocessing(wireValue);
    }
}
