package qa.fanar.core.chat;

/**
 * A content part returned by the assistant in a chat-completion response.
 *
 * <p>Output content is a different sealed hierarchy from input content (see
 * {@link UserContentPart} and {@link AssistantContentPart}) because the allowed shapes diverge:
 * responses can include audio, never include video, and the image shape has no detail hint.</p>
 *
 * <p>Consumers pattern-match over the full hierarchy:</p>
 *
 * <pre>{@code
 * switch (part) {
 *     case TextContent  t -> ui.appendText(t.text());
 *     case ImageContent i -> ui.appendImage(i.url());
 *     case AudioContent a -> ui.appendAudio(a.url());
 * }
 * }</pre>
 */
public sealed interface ResponseContent
        permits TextContent, ImageContent, AudioContent {
}
