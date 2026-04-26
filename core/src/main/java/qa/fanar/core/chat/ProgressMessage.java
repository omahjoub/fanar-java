package qa.fanar.core.chat;

import java.util.Objects;

/**
 * Bilingual progress message carried by a {@link ProgressChunk}.
 *
 * <p>Fanar-exclusive: no OpenAI-shaped stream emits these. Both language strings are always
 * provided on the wire — the record mirrors that invariant by requiring both non-null.</p>
 *
 * @param en English progress text; must not be {@code null}
 * @param ar Arabic progress text; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record ProgressMessage(String en, String ar) {

    public ProgressMessage {
        Objects.requireNonNull(en, "en");
        Objects.requireNonNull(ar, "ar");
    }
}
