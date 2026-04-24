package qa.fanar.core.chat;

import java.util.Objects;

/**
 * Source corpora used by the Islamic RAG model ({@code Fanar-Sadiq}).
 *
 * <p>Mirrors the {@code SourcesEnum} in the Fanar OpenAPI spec. Used on {@code ChatRequest} fields
 * such as {@code preferred_sources}, {@code exclude_sources}, and {@code filter_sources} (modelled
 * in a later PR), and appears on {@code ChatResponse} references to identify where an answer was
 * drawn from.</p>
 */
public enum Source {

    /** islamqa.info — fatwa and Q&amp;A archive. */
    ISLAM_QA("islam_qa"),

    /** islamweb.net — general Islamic content. */
    ISLAMWEB("islamweb"),

    /** islamweb.net fatwa section. */
    ISLAMWEB_FATWA("islamweb_fatwa"),

    /** islamweb.net consultation section. */
    ISLAMWEB_CONSULT("islamweb_consult"),

    /** islamweb.net article section. */
    ISLAMWEB_ARTICLE("islamweb_article"),

    /** islamweb.net library section. */
    ISLAMWEB_LIBRARY("islamweb_library"),

    /** sunnah.com — Hadith collections. */
    SUNNAH("sunnah"),

    /** The Qur'an. */
    QURAN("quran"),

    /** Tafsir — Qur'anic exegesis works. */
    TAFSIR("tafsir"),

    /** dorar.net — Hadith authentication and Islamic knowledge. */
    DORAR("dorar"),

    /** islamonline.net. */
    ISLAMONLINE("islamonline"),

    /** al-Maktaba al-Shamela digital library. */
    SHAMELA("shamela");

    private final String wireValue;

    Source(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The exact string Fanar uses on the wire for this source. */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Look up an enum value by its wire format.
     *
     * @param value the wire-format string; must not be {@code null}
     * @return the matching enum value
     * @throws IllegalArgumentException if no enum value matches
     * @throws NullPointerException     if {@code value} is {@code null}
     */
    public static Source fromWireValue(String value) {
        Objects.requireNonNull(value, "value");
        for (Source source : values()) {
            if (source.wireValue.equals(value)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown Source wire value: " + value);
    }
}
