package qa.fanar.core.chat;

/**
 * A content part that is valid inside a {@link UserMessage}.
 *
 * <p>Sealed hierarchy permitting {@link TextPart}, {@link ImagePart}, and {@link VideoPart}.
 * {@link TextPart} also implements {@link AssistantContentPart}, so the two content-part
 * hierarchies overlap exactly where the API allows text on both sides.</p>
 *
 * @author Oussama Mahjoub
 */
public sealed interface UserContentPart
        permits TextPart, ImagePart, VideoPart {
}
