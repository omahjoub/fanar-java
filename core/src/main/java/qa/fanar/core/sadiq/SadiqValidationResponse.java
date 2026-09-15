package qa.fanar.core.sadiq;

import java.util.Objects;

/**
 * Result of {@code POST /v1/sadiq/validate} — the input text with every verified quotation tagged
 * and referenced.
 *
 * <p>Verified Qur'anic verses are replaced with the authenticated text, wrapped in
 * {@code <quran_start>} / {@code <quran_end>} and followed by a {@code [surah:ayah](quran.com)}
 * markdown reference; verified hadith are wrapped in {@code <hadith_start>} / {@code <hadith_end>}
 * and followed by a {@code [collection:number](sunnah.com)} reference. <strong>Quotations that
 * could not be verified come back as plain, untagged text — that absence is the signal to act
 * on.</strong></p>
 *
 * <p>{@link #text()} is the wire string verbatim, tags and links included. The SDK does not parse
 * it: turning the markup into structured citations is post-processing, which belongs downstream of
 * core (ADR-002, ADR-028). Render it by stripping the tags and keeping the links.</p>
 *
 * @param id   request-correlation identifier
 * @param text the input text with verified quotations tagged and referenced
 *
 * @author Oussama Mahjoub
 */
public record SadiqValidationResponse(String id, String text) {

    public SadiqValidationResponse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
    }
}
