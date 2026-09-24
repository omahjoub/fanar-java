package qa.fanar.core.chat;

import java.util.Objects;

import qa.fanar.core.sadiq.DeepResearchEvent;

/**
 * Streaming event announcing an intermediate processing step, in both English and Arabic.
 *
 * <p>Fanar-exclusive: no OpenAI-shaped SDK surfaces these. Typical content is a short progress
 * description such as {@code "searching corpus"} / {@code "البحث في المصادر"}. UIs render the
 * language that matches the user's locale.</p>
 *
 * <p>The wire format nests the bilingual strings inside {@code progress.message.{en,ar}}; this
 * record flattens one level so the message is available via {@link #message()} directly.
 * Emitted by chat streams and by deep-research streams alike, hence a member of both
 * {@link StreamEvent} and {@link DeepResearchEvent}; a deep-research run announces each research
 * pass this way.</p>
 *
 * @param id      completion id; must not be {@code null}
 * @param created server-side timestamp
 * @param model   wire-format model id; may be {@code null} when the server omits it — the spec
 *                marks it required, but its own deep-research example sends the first progress
 *                event with {@code "model": null}
 * @param message bilingual progress description; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record ProgressChunk(
        String id,
        long created,
        String model,
        ProgressMessage message
) implements StreamEvent, DeepResearchEvent {

    public ProgressChunk {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(message, "message");
    }
}
