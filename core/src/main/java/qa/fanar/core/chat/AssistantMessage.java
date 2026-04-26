package qa.fanar.core.chat;

import java.util.List;

/**
 * Assistant message — a turn from the model in the conversation.
 *
 * <p>At least one of {@code content} or {@code toolCalls} must carry data. An assistant message
 * is allowed to have only tool calls and no content (the model invoked tools but did not speak),
 * only content (typical), or both.</p>
 *
 * <p>Both collections are defensively copied on construction and returned as unmodifiable views.
 * Null input collections are normalized to empty lists so accessors never return {@code null}.</p>
 *
 * @param content    list of content parts (text or refusal); never {@code null}, may be empty
 * @param name       optional speaker name (nullable)
 * @param toolCalls  list of tool calls the assistant invoked; never {@code null}, may be empty
 *
 * @author Oussama Mahjoub
 */
public record AssistantMessage(
        List<AssistantContentPart> content,
        String name,
        List<ToolCall> toolCalls
) implements Message {

    public AssistantMessage {
        content = content == null ? List.of() : List.copyOf(content);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (content.isEmpty() && toolCalls.isEmpty()) {
            throw new IllegalArgumentException(
                    "AssistantMessage must have either content or toolCalls");
        }
    }

    /** Convenience factory for a text-only assistant message (no tool calls, no name). */
    public static AssistantMessage of(String text) {
        return new AssistantMessage(List.of(new TextPart(text)), null, null);
    }
}
