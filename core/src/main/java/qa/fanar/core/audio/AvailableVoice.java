package qa.fanar.core.audio;

import java.util.List;
import java.util.Objects;

/**
 * One voice in the {@code GET /v1/audio/voices} listing.
 *
 * <p>Mirrors the OpenAPI {@code Voice} schema. Only {@code name} and {@code type} are guaranteed
 * by the spec; the descriptive fields are nullable and the {@code languages} list may be empty
 * (typical for personalized voices). {@link #name()} is the identifier accepted by
 * {@code TextToSpeechRequest.voice()} — convert with {@code Voice.of(availableVoice.name())}.</p>
 *
 * <p>Named {@code AvailableVoice} (mirroring {@code models.AvailableModel}) because {@link Voice}
 * is already the request-side voice identifier.</p>
 *
 * @param name      the English voice name — the identifier passed to the TTS endpoint; never
 *                  {@code null}
 * @param nameAr    the Arabic display name, or {@code null} when unavailable
 * @param gender    gender label (for example {@code "Male"}, {@code "Female"}), or {@code null}
 * @param accent    accent label (for example {@code "British"}, {@code "Gulf"},
 *                  {@code "Standard"}), or {@code null}
 * @param languages supported language codes (for example {@code "en"}, {@code "ar"});
 *                  {@code null} when the server omits the field, defensively copied otherwise
 * @param type      whether this is a built-in public voice or a personalized one; never
 *                  {@code null}
 * @param emotion   whether the voice supports emotional synthesis
 *                  ({@code TextToSpeechRequest.withEmotion})
 *
 * @author Oussama Mahjoub
 */
public record AvailableVoice(
        String name,
        String nameAr,
        String gender,
        String accent,
        List<String> languages,
        VoiceType type,
        boolean emotion
) {

    public AvailableVoice {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        languages = languages == null ? null : List.copyOf(languages);
    }
}
