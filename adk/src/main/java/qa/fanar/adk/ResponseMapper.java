package qa.fanar.adk;

import java.util.ArrayList;
import java.util.List;

import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingChunk;
import com.google.genai.types.GroundingChunkRetrievedContext;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.Part;

import qa.fanar.core.chat.ChatChoice;
import qa.fanar.core.chat.ChatMessage;
import qa.fanar.core.chat.ChatResponse;
import qa.fanar.core.chat.CompletionUsage;
import qa.fanar.core.chat.Reference;
import qa.fanar.core.chat.ResponseContent;
import qa.fanar.core.chat.TextContent;
import qa.fanar.core.chat.ToolCall;

/**
 * Fanar responses to ADK {@code LlmResponse}s (ADR-030). One outcome rule serves both the
 * non-streaming response and the aggregated end of a stream: content when there is content,
 * {@code errorCode} plus {@code errorMessage} when there is nothing to show, never
 * {@code errorMessage} alone, because ADK builds no event for that shape.
 */
final class ResponseMapper {

    private ResponseMapper() {
        // static only
    }

    static LlmResponse toLlmResponse(ChatResponse response) {
        if (response.choices().isEmpty()) {
            return outcome(List.of(), null, "Fanar returned no choices", response.usage(), List.of(),
                    response.model());
        }
        ChatChoice choice = response.choices().getFirst();
        ChatMessage message = choice.message();
        List<Part> parts = new ArrayList<>();
        String text = textOf(message.content());
        if (!text.isEmpty()) {
            parts.add(Part.fromText(text));
        }
        for (ToolCall call : message.toolCalls()) {
            // A populated result is a retrieval the server already performed; only a call still
            // awaiting a client round trip is handed to ADK for dispatch.
            if (call.result() == null) {
                parts.add(Part.fromFunctionCall(call.name(), call.arguments()));
            }
        }
        return outcome(parts, choice.finishReason().wireValue(), null, response.usage(),
                message.references(), response.model());
    }

    /**
     * @param parts        what the model produced; empty when nothing came back
     * @param wireFinish   Fanar's finish reason, or {@code null} when none was seen
     * @param error        the error text of a stream that reported one, else {@code null}
     * @param usage        token usage, or {@code null} when none was reported
     * @param references   Sadiq citations, empty when none
     * @param modelVersion the model the server reported
     */
    static LlmResponse outcome(List<Part> parts, String wireFinish, String error, CompletionUsage usage,
                               List<Reference> references, String modelVersion) {
        LlmResponse.Builder builder = LlmResponse.builder().modelVersion(modelVersion);
        FinishReason finish = wireFinish == null ? null : finishReason(wireFinish);
        if (finish != null) {
            builder.finishReason(finish);
        }
        boolean stopped = finish != null && finish.knownEnum() == FinishReason.Known.STOP;

        if (error != null) {
            builder.errorCode(errorCode(finish)).errorMessage(error);
            if (!parts.isEmpty()) {
                builder.content(modelContent(parts));
            }
        } else if (!parts.isEmpty() || stopped) {
            builder.content(modelContent(parts.isEmpty() ? List.of(Part.fromText("")) : parts));
        } else {
            builder.errorCode(errorCode(finish)).errorMessage("Fanar returned no content"
                    + (wireFinish == null ? "" : " (finish reason: " + wireFinish + ")"));
        }
        if (usage != null) {
            builder.usageMetadata(usage(usage));
        }
        if (!references.isEmpty()) {
            builder.groundingMetadata(grounding(references));
        }
        return builder.build();
    }

    /** Fanar's finish reasons in ADK's known vocabulary, so ADK's telemetry and UI read them. */
    static FinishReason finishReason(String wireValue) {
        return switch (wireValue) {
            case "stop", "tool_calls", "function_call" -> new FinishReason(FinishReason.Known.STOP);
            case "length" -> new FinishReason(FinishReason.Known.MAX_TOKENS);
            case "content_filter" -> new FinishReason(FinishReason.Known.SAFETY);
            default -> new FinishReason(FinishReason.Known.OTHER);
        };
    }

    private static FinishReason errorCode(FinishReason finish) {
        return finish == null ? new FinishReason(FinishReason.Known.OTHER) : finish;
    }

    static Content modelContent(List<Part> parts) {
        return Content.builder().role("model").parts(parts).build();
    }

    private static GenerateContentResponseUsageMetadata usage(CompletionUsage usage) {
        return GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(usage.promptTokens())
                .candidatesTokenCount(usage.completionTokens())
                .totalTokenCount(usage.totalTokens())
                .build();
    }

    /** Each Sadiq reference becomes a retrieved-context grounding chunk: the source and the quoted text. */
    private static GroundingMetadata grounding(List<Reference> references) {
        List<GroundingChunk> chunks = new ArrayList<>();
        for (Reference reference : references) {
            chunks.add(GroundingChunk.builder()
                    .retrievedContext(GroundingChunkRetrievedContext.builder()
                            .title(reference.source())
                            .text(reference.content())
                            .build())
                    .build());
        }
        return GroundingMetadata.builder().groundingChunks(chunks).build();
    }

    private static String textOf(List<ResponseContent> content) {
        StringBuilder text = new StringBuilder();
        for (ResponseContent part : content) {
            if (part instanceof TextContent t) {
                text.append(t.text());
            }
        }
        return text.toString();
    }
}
