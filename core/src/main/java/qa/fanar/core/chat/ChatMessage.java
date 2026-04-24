package qa.fanar.core.chat;

import java.util.List;

/**
 * The assistant's message inside a {@link ChatChoice} on the response side.
 *
 * <p>Distinct from the request-side {@link AssistantMessage} because the shape diverges: the
 * response carries {@link Reference}s (Fanar-Sadiq Islamic-RAG sources) and returns output-side
 * {@link ResponseContent} variants (text / image / audio — no refusal content part on output;
 * refusals surface via {@link FinishReason#CONTENT_FILTER} or the exception hierarchy).</p>
 *
 * <p>All three collections are defensively copied on construction and returned as unmodifiable
 * views. Null input is normalized to an empty list so accessors never return {@code null}.</p>
 *
 * @param content    content parts (text / image / audio); never {@code null}, may be empty
 * @param references authenticated source references; never {@code null}, may be empty
 * @param toolCalls  tool invocations the assistant made; never {@code null}, may be empty
 */
public record ChatMessage(
        List<ResponseContent> content,
        List<Reference> references,
        List<ToolCall> toolCalls
) {

    public ChatMessage {
        content = content == null ? List.of() : List.copyOf(content);
        references = references == null ? List.of() : List.copyOf(references);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
