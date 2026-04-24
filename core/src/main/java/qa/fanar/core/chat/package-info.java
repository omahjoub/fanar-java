/**
 * Chat-domain types for the Fanar Java SDK.
 *
 * <p>Covers every type a caller needs to build chat-completion requests, interpret responses, and
 * consume streaming events from Fanar's {@code /v1/chat/completions} endpoint.</p>
 *
 * <h2>Messages</h2>
 * <p>{@link qa.fanar.core.chat.Message} is a sealed interface permitting the five role variants
 * Fanar supports: system, user, assistant, thinking, and thinking-user. Each variant is a record
 * with role-specific content constraints enforced through the type system:</p>
 * <ul>
 *   <li>{@link qa.fanar.core.chat.SystemMessage} — plain text content.</li>
 *   <li>{@link qa.fanar.core.chat.UserMessage} — list of
 *       {@link qa.fanar.core.chat.UserContentPart} (text, image, or video).</li>
 *   <li>{@link qa.fanar.core.chat.AssistantMessage} — list of
 *       {@link qa.fanar.core.chat.AssistantContentPart} (text or refusal), plus optional
 *       {@link qa.fanar.core.chat.ToolCall}s.</li>
 *   <li>{@link qa.fanar.core.chat.ThinkingMessage} and
 *       {@link qa.fanar.core.chat.ThinkingUserMessage} — plain text content; used with the
 *       role-based thinking protocol on {@code Fanar-C-2-27B}.</li>
 * </ul>
 *
 * <p>The {@link qa.fanar.core.chat.UserContentPart} and
 * {@link qa.fanar.core.chat.AssistantContentPart} sealed hierarchies overlap at
 * {@link qa.fanar.core.chat.TextPart}, which implements both — text is accepted by both roles.</p>
 */
package qa.fanar.core.chat;
