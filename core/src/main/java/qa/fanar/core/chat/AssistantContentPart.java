package qa.fanar.core.chat;

/**
 * A content part that is valid inside an {@link AssistantMessage}.
 *
 * <p>Sealed hierarchy permitting {@link TextPart} and {@link RefusalPart}. The assistant either
 * returns regular text or an explicit structured refusal; it does not send image or video parts
 * as content (image generation has its own endpoint; videos are input-only).</p>
 */
public sealed interface AssistantContentPart
        permits TextPart, RefusalPart {
}
