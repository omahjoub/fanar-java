package qa.fanar.spring.ai;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import qa.fanar.core.FanarClient;
import qa.fanar.core.chat.DoneChunk;
import qa.fanar.core.chat.ErrorChunk;
import qa.fanar.core.chat.ChatChoice;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.ResponseContent;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.chat.SystemMessage;
import qa.fanar.core.chat.TextContent;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.chat.ToolCallChunk;
import qa.fanar.core.chat.ToolResultChunk;
import qa.fanar.core.chat.UserMessage;

/**
 * Spring AI {@link ChatModel} adapter backed by the Fanar Java SDK.
 *
 * <p>Maps Spring AI's {@link Prompt} (a list of {@link Message}s + optional {@link ChatOptions})
 * onto a Fanar {@link ChatRequest}, dispatches via {@link FanarClient#chat()}, and shapes the
 * Fanar response back into a Spring AI {@link ChatResponse}. Streaming bridges Fanar's
 * {@code Flow.Publisher<StreamEvent>} to Reactor {@code Flux<ChatResponse>} with one
 * {@code ChatResponse} per Fanar token chunk — Spring AI's {@code ChatClient} accumulates them.</p>
 *
 * <p>What the adapter does <em>not</em> do:</p>
 * <ul>
 *   <li><b>Tool calls.</b> Fanar's API rejects user-supplied tools (it returns server-internal
 *       Sadiq retriever telemetry as {@code tool_calls}, not user tools). If a {@link Prompt}
 *       carries tools, we simply do not forward them — Spring AI's tool-orchestration loop will
 *       see no tool-call response and fall through to the model's text reply.</li>
 *   <li><b>Native structured output.</b> Fanar does not expose a {@code response_format} field,
 *       so {@link ChatOptions#getModel()}-level structured output options are ignored. Spring AI's
 *       prompt-engineering converters ({@code BeanOutputConverter}) still work — they shape the
 *       prompt text, no model-side flag needed.</li>
 *   <li><b>Embeddings.</b> Different model layer, not this class. Fanar has no embeddings
 *       endpoint at all (see project README).</li>
 * </ul>
 *
 * @author Oussama Mahjoub
 */
public final class FanarChatModel implements ChatModel {

    private final FanarClient fanar;
    private final qa.fanar.core.chat.ChatModel defaultModel;

    /**
     * Construct an adapter that defaults to the supplied Fanar model when a {@link Prompt} does
     * not carry a {@link ChatOptions#getModel()} override.
     *
     * @param fanar        the auto-wired SDK client
     * @param defaultModel typed Fanar model (e.g. {@code ChatModel.FANAR})
     */
    public FanarChatModel(FanarClient fanar, qa.fanar.core.chat.ChatModel defaultModel) {
        this.fanar = Objects.requireNonNull(fanar, "fanar");
        this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        ChatRequest request = toFanarRequest(prompt);
        qa.fanar.core.chat.ChatResponse fanarResponse = fanar.chat().send(request);
        return toSpringAiResponse(fanarResponse);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        ChatRequest request = toFanarRequest(prompt);
        return JdkFlowAdapter.flowPublisherToFlux(fanar.chat().stream(request))
                .mapNotNull(FanarChatModel::toSpringAiChunk);
    }

    // ---------------------------------------------------------------------------------------
    // Request mapping: Prompt + ChatOptions -> ChatRequest
    // ---------------------------------------------------------------------------------------

    private ChatRequest toFanarRequest(Prompt prompt) {
        ChatRequest.Builder builder = ChatRequest.builder().model(resolveModel(prompt));
        for (Message message : prompt.getInstructions()) {
            switch (message.getMessageType()) {
                case USER      -> builder.addMessage(UserMessage.of(message.getText()));
                case SYSTEM    -> builder.addMessage(SystemMessage.of(message.getText()));
                case ASSISTANT -> builder.addMessage(qa.fanar.core.chat.AssistantMessage.of(message.getText()));
                // TOOL messages aren't supported on the Fanar wire — silently skip rather than
                // crash, so apps that wrap us with Spring AI's tool-callback advisor still
                // work in degraded mode (no tool round-trips, model answers from text alone).
                case TOOL      -> { /* no-op */ }
            }
        }
        applyOptions(builder, prompt.getOptions());
        return builder.build();
    }

    private qa.fanar.core.chat.ChatModel resolveModel(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options != null && options.getModel() != null && !options.getModel().isBlank()) {
            return qa.fanar.core.chat.ChatModel.of(options.getModel());
        }
        return defaultModel;
    }

    private static void applyOptions(ChatRequest.Builder builder, ChatOptions options) {
        if (options == null) {
            return;
        }
        if (options.getTemperature() != null) {
            builder.temperature(options.getTemperature());
        }
        if (options.getTopP() != null) {
            builder.topP(options.getTopP());
        }
        if (options.getTopK() != null) {
            builder.topK(options.getTopK());
        }
        if (options.getMaxTokens() != null) {
            builder.maxTokens(options.getMaxTokens());
        }
        if (options.getFrequencyPenalty() != null) {
            builder.frequencyPenalty(options.getFrequencyPenalty());
        }
        if (options.getPresencePenalty() != null) {
            builder.presencePenalty(options.getPresencePenalty());
        }
        if (options.getStopSequences() != null && !options.getStopSequences().isEmpty()) {
            builder.stop(options.getStopSequences());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Response mapping (sync): Fanar ChatResponse -> Spring AI ChatResponse
    // ---------------------------------------------------------------------------------------

    private static ChatResponse toSpringAiResponse(qa.fanar.core.chat.ChatResponse fanarResponse) {
        List<Generation> generations = fanarResponse.choices().stream()
                .map(FanarChatModel::toGeneration)
                .toList();
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .id(fanarResponse.id())
                .model(fanarResponse.model())
                .build();
        return new ChatResponse(generations, metadata);
    }

    private static Generation toGeneration(ChatChoice choice) {
        AssistantMessage assistant = new AssistantMessage(textOf(choice.message().content()));
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason(choice.finishReason().wireValue())
                .build();
        return new Generation(assistant, generationMetadata);
    }

    private static String textOf(List<ResponseContent> parts) {
        // Fanar's chat-completion JSON adapter only ever decodes content as a single TextContent
        // (or an empty list — see ResponseContentListDeserializer). A non-TextContent entry
        // would mean the codec contract was violated upstream; we'd rather surface that as a
        // ClassCastException than mask it with a fallback.
        return parts.isEmpty() ? "" : ((TextContent) parts.getFirst()).text();
    }

    // ---------------------------------------------------------------------------------------
    // Response mapping (stream): StreamEvent -> Spring AI ChatResponse chunk
    // ---------------------------------------------------------------------------------------

    /**
     * Project one Fanar streaming event onto a Spring AI {@link ChatResponse} chunk. Returns
     * {@code null} for {@link ProgressChunk} (Fanar-Sadiq intermediate progress messages — no
     * Spring AI equivalent on the chat surface) so {@link Flux#mapNotNull} drops them.
     *
     * <p>Package-private so unit tests can drive the four StreamEvent variants directly without
     * hand-crafting Fanar's SSE wire format for each.</p>
     */
    static ChatResponse toSpringAiChunk(StreamEvent event) {
        return switch (event) {
            case TokenChunk t      -> chunkResponse(t.id(), t.model(), tokenText(t), null);
            case DoneChunk d       -> chunkResponse(d.id(), d.model(), "", finishReasonOf(d));
            case ErrorChunk e      -> chunkResponse(e.id(), e.model(), "", "error");
            // Fanar-Sadiq emits ProgressChunks ("searching corpus", etc.) — Spring AI's chat
            // surface has no equivalent, so drop them. Fanar tool_calls are Sadiq retriever
            // telemetry, not user-tool round-trips, and Spring AI's tool-callback machinery
            // is the wrong consumer for them — drop those too.
            case ProgressChunk p   -> null;
            case ToolCallChunk t   -> null;
            case ToolResultChunk t -> null;
        };
    }

    private static ChatResponse chunkResponse(String id, String model, String text, String finishReason) {
        AssistantMessage assistant = new AssistantMessage(text);
        ChatGenerationMetadata.Builder genBuilder = ChatGenerationMetadata.builder();
        if (finishReason != null) {
            genBuilder.finishReason(finishReason);
        }
        Generation generation = new Generation(assistant, genBuilder.build());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .id(id)
                .model(model)
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    // Both helpers index into choices.getFirst() without an emptiness guard: every TokenChunk
    // and DoneChunk Fanar emits carries at least one choice (the wire spec requires it). If that
    // ever changes upstream, an IndexOutOfBoundsException surfaces immediately — we'd rather see
    // the contract violation than silently mask it with a fallback.

    private static String tokenText(TokenChunk chunk) {
        return chunk.choices().getFirst().content();
    }

    private static String finishReasonOf(DoneChunk done) {
        return done.choices().getFirst().finishReason();
    }
}
