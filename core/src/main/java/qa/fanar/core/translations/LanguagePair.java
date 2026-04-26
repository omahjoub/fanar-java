package qa.fanar.core.translations;

import java.util.Objects;
import java.util.Set;

/**
 * Source–target language pair for a translation request.
 *
 * <p>Mirrors the {@code TranslationLangPairs} schema in the OpenAPI spec, but open: callers can
 * target a new pair via {@link #of(String)} the day Fanar ships it. The wire format is two
 * lowercase ISO 639-1 codes joined by a dash: {@code "en-ar"}, {@code "ar-en"}, …</p>
 *
 * @param wireValue the exact string Fanar accepts (e.g. {@code "en-ar"})
 *
 * @author Oussama Mahjoub
 */
public record LanguagePair(String wireValue) {

    /** English → Arabic. */
    public static final LanguagePair EN_AR = new LanguagePair("en-ar");

    /** Arabic → English. */
    public static final LanguagePair AR_EN = new LanguagePair("ar-en");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<LanguagePair> KNOWN = Set.of(EN_AR, AR_EN);

    public LanguagePair {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new LanguagePair(wireValue)}; provided for API symmetry. */
    public static LanguagePair of(String wireValue) {
        return new LanguagePair(wireValue);
    }
}
