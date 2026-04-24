package qa.fanar.core.chat;

import java.util.Objects;

/**
 * Streaming event announcing an intermediate processing step, in both English and Arabic.
 *
 * <p>Fanar-exclusive: no OpenAI-shaped SDK surfaces these. Typical content is a short progress
 * description such as {@code "searching corpus"} / {@code "البحث في المصادر"}. UIs render the
 * language that matches the user's locale.</p>
 *
 * <p>The wire format nests the bilingual strings inside {@code progress.message.{en,ar}}; this
 * record flattens one level so the message is available via {@link #message()} directly.</p>
 *
 * @param id      completion id; must not be {@code null}
 * @param created server-side timestamp
 * @param model   wire-format model id; must not be {@code null}
 * @param message bilingual progress description; must not be {@code null}
 */
public record ProgressChunk(
        String id,
        long created,
        String model,
        ProgressMessage message
) implements StreamEvent {

    public ProgressChunk {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(message, "message");
    }
}
