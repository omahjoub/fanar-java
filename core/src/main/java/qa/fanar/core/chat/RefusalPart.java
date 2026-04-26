package qa.fanar.core.chat;

import java.util.Objects;

/**
 * A structured refusal emitted by the assistant.
 *
 * <p>When the model declines a request for safety, policy, or capability reasons, it may return a
 * {@code RefusalPart} rather than a normal {@link TextPart}. This is distinct from
 * {@code FanarContentFilterException}, which is raised when Fanar's moderation layer blocks
 * before the model sees the input — a {@code RefusalPart} is the <em>model's own</em> refusal.</p>
 *
 * @param refusal the human-readable refusal text; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record RefusalPart(String refusal) implements AssistantContentPart {

    public RefusalPart {
        Objects.requireNonNull(refusal, "refusal");
    }
}
