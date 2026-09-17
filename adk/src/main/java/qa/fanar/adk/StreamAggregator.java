package qa.fanar.adk;

import java.util.List;
import java.util.StringJoiner;

import com.google.adk.models.LlmResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

import qa.fanar.core.chat.ChoiceError;
import qa.fanar.core.chat.ChoiceFinal;
import qa.fanar.core.chat.ChoiceToken;
import qa.fanar.core.chat.ChoiceToolCall;
import qa.fanar.core.chat.ChoiceToolResult;
import qa.fanar.core.chat.CompletionUsage;
import qa.fanar.core.chat.DoneChunk;
import qa.fanar.core.chat.ErrorChunk;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.Reference;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.chat.ToolCallChunk;
import qa.fanar.core.chat.ToolResultChunk;

/**
 * Fanar's stream to ADK's streaming protocol (ADR-030): every token delta becomes a
 * {@code partial(true)} response, and completion of the publisher — not the {@code DoneChunk},
 * which a truncated stream never sends — yields exactly one non-partial response carrying the
 * whole turn. One instance per subscription.
 */
final class StreamAggregator {

    private final String requestedModel;
    private final StringBuilder text = new StringBuilder();
    private String model;
    private String finishReason;
    private CompletionUsage usage;
    private String error;
    private List<Reference> references = List.of();

    StreamAggregator(String requestedModel) {
        this.requestedModel = requestedModel;
    }

    Flowable<LlmResponse> onEvent(StreamEvent event) {
        model = event.model();
        return switch (event) {
            case TokenChunk chunk -> onToken(chunk);
            case DoneChunk chunk -> onDone(chunk);
            case ErrorChunk chunk -> onError(chunk);
            // Server-side retrieval telemetry: nothing for ADK to dispatch, but the finish reason
            // rides on these choices too.
            case ToolCallChunk chunk -> {
                for (ChoiceToolCall choice : chunk.choices()) {
                    noteFinish(choice.finishReason());
                }
                yield Flowable.empty();
            }
            case ToolResultChunk chunk -> {
                for (ChoiceToolResult choice : chunk.choices()) {
                    noteFinish(choice.finishReason());
                }
                yield Flowable.empty();
            }
            case ProgressChunk chunk -> Flowable.empty();
        };
    }

    Flowable<LlmResponse> onComplete() {
        List<Part> parts = text.isEmpty() ? List.of() : List.of(Part.fromText(text.toString()));
        return Flowable.just(ResponseMapper.outcome(parts, finishReason, error, usage, references,
                model == null ? requestedModel : model));
    }

    private Flowable<LlmResponse> onToken(TokenChunk chunk) {
        StringBuilder delta = new StringBuilder();
        for (ChoiceToken choice : chunk.choices()) {
            delta.append(choice.content());
            noteFinish(choice.finishReason());
        }
        if (delta.isEmpty()) {
            return Flowable.empty();
        }
        text.append(delta);
        return Flowable.just(LlmResponse.builder()
                .partial(true)
                .content(ResponseMapper.modelContent(List.of(Part.fromText(delta.toString()))))
                .modelVersion(model)
                .build());
    }

    private Flowable<LlmResponse> onDone(DoneChunk chunk) {
        usage = chunk.usage();
        for (ChoiceFinal choice : chunk.choices()) {
            noteFinish(choice.finishReason());
            if (!choice.references().isEmpty()) {
                references = choice.references();
            }
        }
        return Flowable.empty();
    }

    private Flowable<LlmResponse> onError(ErrorChunk chunk) {
        StringJoiner message = new StringJoiner("\n");
        for (ChoiceError choice : chunk.choices()) {
            message.add(choice.content());
            noteFinish(choice.finishReason());
        }
        error = message.toString();
        return Flowable.empty();
    }

    private void noteFinish(String finish) {
        if (finish != null) {
            finishReason = finish;
        }
    }
}
