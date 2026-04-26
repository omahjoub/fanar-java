package qa.fanar.core.chat;

/**
 * A single message in a chat-completion conversation.
 *
 * <p>Sealed hierarchy permitting the five role variants Fanar supports: system, user, assistant,
 * thinking, and thinking-user. Consumers pattern-match on {@code Message}:</p>
 *
 * <pre>{@code
 * switch (message) {
 *     case SystemMessage s        -> ...
 *     case UserMessage u          -> ...
 *     case AssistantMessage a     -> ...
 *     case ThinkingMessage t      -> ...
 *     case ThinkingUserMessage tu -> ...
 * }
 * }</pre>
 *
 * <p>The compiler verifies this switch is exhaustive. Adding a new permitted variant in a future
 * release is a source-breaking change and will be called out per the stability policy.</p>
 *
 * @author Oussama Mahjoub
 */
public sealed interface Message
        permits SystemMessage, UserMessage, AssistantMessage,
                ThinkingMessage, ThinkingUserMessage {
}
