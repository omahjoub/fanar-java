package qa.fanar.core.chat;

import java.util.Objects;

/**
 * An authenticated source citation returned by {@code Fanar-Sadiq} alongside an Islamic-RAG
 * answer.
 *
 * <p>Fanar-exclusive: no OpenAI-shaped response surfaces these. A typical response from
 * {@code Fanar-Sadiq} contains a list of references describing where each claim in the answer
 * was drawn from (Qur'an, hadith, tafsir, etc.).</p>
 *
 * <p>The {@code source} field is kept as a {@link String} rather than a {@link Source} enum
 * because Fanar may return custom values (e.g. {@code digital_seerah_*}) outside the predefined
 * set. Callers can pass the value to {@link Source#fromWireValue(String)} when a typed value is
 * needed.</p>
 *
 * @param index   character offset in the response where this reference is cited
 * @param number  the reference number as rendered in the response text
 * @param source  the source identifier; must not be {@code null}
 * @param content the quoted text from the source; must not be {@code null}
 */
public record Reference(int index, int number, String source, String content) {

    public Reference {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(content, "content");
    }
}
