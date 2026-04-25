package qa.fanar.core.chat;

import java.util.Objects;
import java.util.Set;

/**
 * Source corpora used by the Islamic RAG model ({@code Fanar-Sadiq}).
 *
 * <p>Mirrors the {@code SourcesEnum} in the Fanar OpenAPI spec — but open: if Fanar adds a new
 * corpus, callers can target it via {@link #of(String)} without waiting for an SDK release.
 * Used on {@code ChatRequest} fields like {@code preferred_sources}, {@code exclude_sources},
 * and {@code filter_sources}, and appears on response references to identify where an answer
 * was drawn from.</p>
 *
 * @param wireValue the exact string Fanar uses on the wire for this source
 */
public record Source(String wireValue) {

    /** islamqa.info — fatwa and Q&amp;A archive. */
    public static final Source ISLAM_QA          = new Source("islam_qa");

    /** islamweb.net — general Islamic content. */
    public static final Source ISLAMWEB          = new Source("islamweb");

    /** islamweb.net fatwa section. */
    public static final Source ISLAMWEB_FATWA    = new Source("islamweb_fatwa");

    /** islamweb.net consultation section. */
    public static final Source ISLAMWEB_CONSULT  = new Source("islamweb_consult");

    /** islamweb.net article section. */
    public static final Source ISLAMWEB_ARTICLE  = new Source("islamweb_article");

    /** islamweb.net library section. */
    public static final Source ISLAMWEB_LIBRARY  = new Source("islamweb_library");

    /** sunnah.com — Hadith collections. */
    public static final Source SUNNAH            = new Source("sunnah");

    /** The Qur'an. */
    public static final Source QURAN             = new Source("quran");

    /** Tafsir — Qur'anic exegesis works. */
    public static final Source TAFSIR            = new Source("tafsir");

    /** dorar.net — Hadith authentication and Islamic knowledge. */
    public static final Source DORAR             = new Source("dorar");

    /** islamonline.net. */
    public static final Source ISLAMONLINE       = new Source("islamonline");

    /** al-Maktaba al-Shamela digital library. */
    public static final Source SHAMELA           = new Source("shamela");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<Source> KNOWN = Set.of(
            ISLAM_QA, ISLAMWEB, ISLAMWEB_FATWA, ISLAMWEB_CONSULT, ISLAMWEB_ARTICLE,
            ISLAMWEB_LIBRARY, SUNNAH, QURAN, TAFSIR, DORAR, ISLAMONLINE, SHAMELA);

    public Source {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new Source(wireValue)}; provided for API symmetry with other types. */
    public static Source of(String wireValue) {
        return new Source(wireValue);
    }
}
