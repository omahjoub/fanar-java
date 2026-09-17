package qa.fanar.adk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;

import qa.fanar.core.chat.AssistantMessage;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.ImagePart;
import qa.fanar.core.chat.Message;
import qa.fanar.core.chat.SystemMessage;
import qa.fanar.core.chat.TextPart;
import qa.fanar.core.chat.UserContentPart;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.core.chat.VideoPart;

/**
 * ADK {@code LlmRequest} to Fanar {@code ChatRequest} (ADR-030). Collects every feature Fanar
 * cannot honour while mapping, then applies the model's {@link UnsupportedFeaturePolicy} before
 * the request is built, so a rejected request never reaches the wire.
 */
final class RequestMapper {

    /** The built-in tool slots of a genai {@code Tool}, for naming one that carries no function declaration. */
    private static final Map<String, Function<Tool, Optional<?>>> BUILT_IN_TOOLS = builtInTools();

    private RequestMapper() {
        // static only
    }

    static ChatRequest toChatRequest(LlmRequest request, ChatModel model, FanarLlmOptions options) {
        Set<UnsupportedFeature> unsupported = new LinkedHashSet<>();
        Optional<GenerateContentConfig> config = request.config();
        List<Message> messages = new ArrayList<>();

        config.flatMap(GenerateContentConfig::systemInstruction)
                .map(RequestMapper::textOf)
                .filter(text -> !text.isEmpty())
                .ifPresent(text -> messages.add(SystemMessage.of(text)));
        for (Content content : request.contents()) {
            Message message = toMessage(content, unsupported);
            if (message != null) {
                messages.add(message);
            }
        }
        collectTools(request, config, unsupported);
        config.ifPresent(c -> collectStructuredOutput(c, unsupported));

        // Under IGNORE the drop must still leave something to send; an empty conversation is not
        // a request Fanar can answer, and core's "messages must not be empty" would name the wrong cause.
        if (!unsupported.isEmpty()
                && (options.unsupportedFeatures() == UnsupportedFeaturePolicy.REJECT || messages.isEmpty())) {
            throw new UnsupportedFeatureException(List.copyOf(unsupported), options.unsupportedFeatures());
        }

        ChatRequest.Builder builder = ChatRequest.builder().model(model).messages(messages);
        config.ifPresent(c -> applyConfig(c, builder));
        applyOptions(options, builder);
        return builder.build();
    }

    // --- messages ------------------------------------------------------------------------------

    private static Message toMessage(Content content, Set<UnsupportedFeature> unsupported) {
        String role = content.role().orElse("user");
        boolean modelTurn = role.equalsIgnoreCase("model") || role.equalsIgnoreCase("assistant");
        StringBuilder text = new StringBuilder();
        List<UserContentPart> media = new ArrayList<>();

        for (Part part : content.parts().orElse(List.of())) {
            if (part.thought().orElse(false)) {
                continue;
            }
            if (part.text().isPresent()) {
                appendLine(text, part.text().get());
            } else if (part.fileData().isPresent()) {
                collectFileData(part.fileData().get(), media, unsupported);
            } else if (part.inlineData().isPresent()) {
                unsupported.add(UnsupportedFeature.part("inline data of type '"
                        + part.inlineData().get().mimeType().orElse("unknown") + "'"));
            } else if (part.functionCall().isPresent()) {
                unsupported.add(UnsupportedFeature.part("a function call part"));
            } else if (part.functionResponse().isPresent()) {
                unsupported.add(UnsupportedFeature.part("a function response part"));
            } else if (part.toolCall().isPresent()) {
                unsupported.add(UnsupportedFeature.part("a tool call part"));
            } else if (part.toolResponse().isPresent()) {
                unsupported.add(UnsupportedFeature.part("a tool response part"));
            } else if (part.executableCode().isPresent() || part.codeExecutionResult().isPresent()) {
                unsupported.add(UnsupportedFeature.part("a code execution part"));
            }
            // anything else (a bare thought signature, an empty part) has nothing to send
        }

        if (modelTurn) {
            if (!media.isEmpty()) {
                unsupported.add(UnsupportedFeature.part("media in a model turn"));
            }
            return text.isEmpty() ? null : AssistantMessage.of(text.toString());
        }
        if (text.isEmpty() && media.isEmpty()) {
            return null;
        }
        List<UserContentPart> parts = new ArrayList<>();
        if (!text.isEmpty()) {
            parts.add(new TextPart(text.toString()));
        }
        parts.addAll(media);
        return new UserMessage(parts, null);
    }

    private static void collectFileData(FileData file, List<UserContentPart> media, Set<UnsupportedFeature> unsupported) {
        String mime = file.mimeType().orElse("");
        Optional<String> uri = file.fileUri();
        if (uri.isEmpty()) {
            unsupported.add(UnsupportedFeature.part("file data without a URI"));
        } else if (mime.startsWith("image/")) {
            media.add(ImagePart.of(uri.get()));
        } else if (mime.startsWith("video/")) {
            media.add(new VideoPart(uri.get()));
        } else {
            unsupported.add(UnsupportedFeature.part("file data of type '" + mime + "'"));
        }
    }

    /** Text parts of one content, one per line; the same rule for the system instruction and the turns. */
    private static String textOf(Content content) {
        StringBuilder text = new StringBuilder();
        for (Part part : content.parts().orElse(List.of())) {
            part.text().ifPresent(t -> appendLine(text, t));
        }
        return text.toString();
    }

    private static void appendLine(StringBuilder text, String line) {
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(line);
    }

    // --- features Fanar cannot honour ----------------------------------------------------------

    private static void collectTools(LlmRequest request, Optional<GenerateContentConfig> config,
                                     Set<UnsupportedFeature> unsupported) {
        Set<String> names = new LinkedHashSet<>(request.tools().keySet());
        List<String> builtIns = new ArrayList<>();
        config.flatMap(GenerateContentConfig::tools).ifPresent(tools -> tools.forEach(tool -> {
            List<FunctionDeclaration> declarations = tool.functionDeclarations().orElse(List.of());
            if (declarations.isEmpty()) {
                builtIns.add(builtInName(tool));
            }
            declarations.forEach(declaration -> declaration.name().ifPresent(names::add));
        }));
        names.forEach(name -> unsupported.add(UnsupportedFeature.tool("tool '" + name + "'")));
        builtIns.forEach(kind -> unsupported.add(UnsupportedFeature.tool("a built-in tool (" + kind + ")")));
    }

    /** ADK's Google-only tools ride in a {@code Tool} without declarations; name the slot(s) they fill. */
    private static String builtInName(Tool tool) {
        String kinds = BUILT_IN_TOOLS.entrySet().stream()
                .filter(entry -> entry.getValue().apply(tool).isPresent())
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(", "));
        return kinds.isEmpty() ? "unknown" : kinds;
    }

    private static Map<String, Function<Tool, Optional<?>>> builtInTools() {
        Map<String, Function<Tool, Optional<?>>> slots = new LinkedHashMap<>();
        slots.put("codeExecution", Tool::codeExecution);
        slots.put("googleSearch", Tool::googleSearch);
        slots.put("googleSearchRetrieval", Tool::googleSearchRetrieval);
        slots.put("googleMaps", Tool::googleMaps);
        slots.put("computerUse", Tool::computerUse);
        slots.put("retrieval", Tool::retrieval);
        slots.put("urlContext", Tool::urlContext);
        slots.put("fileSearch", Tool::fileSearch);
        slots.put("enterpriseWebSearch", Tool::enterpriseWebSearch);
        slots.put("mcpServers", Tool::mcpServers);
        slots.put("parallelAiSearch", Tool::parallelAiSearch);
        return Map.copyOf(slots);
    }

    private static void collectStructuredOutput(GenerateContentConfig config, Set<UnsupportedFeature> unsupported) {
        if (config.responseSchema().isPresent() || config.responseJsonSchema().isPresent()) {
            unsupported.add(UnsupportedFeature.outputSchema("an output schema"));
        } else {
            config.responseMimeType()
                    .filter(mime -> !"text/plain".equals(mime))
                    .ifPresent(mime -> unsupported.add(UnsupportedFeature.outputSchema("response MIME type '" + mime + "'")));
        }
    }

    // --- knobs ---------------------------------------------------------------------------------

    /** {@code candidateCount} is not forwarded: ADK's {@code LlmResponse} carries one candidate. */
    private static void applyConfig(GenerateContentConfig config, ChatRequest.Builder builder) {
        config.temperature().ifPresent(v -> builder.temperature(decimal(v)));
        config.topP().ifPresent(v -> builder.topP(decimal(v)));
        config.topK().ifPresent(v -> builder.topK(Math.round(v)));
        config.maxOutputTokens().ifPresent(builder::maxTokens);
        config.stopSequences().filter(stop -> !stop.isEmpty()).ifPresent(builder::stop);
        config.presencePenalty().ifPresent(v -> builder.presencePenalty(decimal(v)));
        config.frequencyPenalty().ifPresent(v -> builder.frequencyPenalty(decimal(v)));
        config.responseLogprobs().ifPresent(builder::logprobs);
        config.logprobs().ifPresent(builder::topLogprobs);
    }

    /**
     * ADK carries {@code Float}; {@code ChatRequest} carries {@code Double}. Widening keeps the
     * binary value, so {@code 0.7f} would go on the wire as {@code 0.699999988079071}; the decimal
     * string keeps what the caller wrote.
     */
    static double decimal(Float value) {
        return Double.parseDouble(Float.toString(value));
    }

    private static void applyOptions(FanarLlmOptions o, ChatRequest.Builder builder) {
        if (o.persona() != null) {
            builder.persona(o.persona());
        }
        if (o.madhab() != null) {
            builder.madhab(o.madhab());
        }
        if (o.enableThinking() != null) {
            builder.enableThinking(o.enableThinking());
        }
        if (o.restrictToIslamic() != null) {
            builder.restrictToIslamic(o.restrictToIslamic());
        }
        if (o.bookNames() != null) {
            builder.bookNames(o.bookNames());
        }
        if (o.preferredSources() != null) {
            builder.preferredSources(o.preferredSources());
        }
        if (o.excludeSources() != null) {
            builder.excludeSources(o.excludeSources());
        }
        if (o.filterSources() != null) {
            builder.filterSources(o.filterSources());
        }
        if (o.logitBias() != null) {
            builder.logitBias(o.logitBias());
        }
        if (o.minP() != null) {
            builder.minP(o.minP());
        }
        if (o.repetitionPenalty() != null) {
            builder.repetitionPenalty(o.repetitionPenalty());
        }
        if (o.bestOf() != null) {
            builder.bestOf(o.bestOf());
        }
        if (o.lengthPenalty() != null) {
            builder.lengthPenalty(o.lengthPenalty());
        }
        if (o.earlyStopping() != null) {
            builder.earlyStopping(o.earlyStopping());
        }
        if (o.stopTokenIds() != null) {
            builder.stopTokenIds(o.stopTokenIds());
        }
        if (o.ignoreEos() != null) {
            builder.ignoreEos(o.ignoreEos());
        }
        if (o.minTokens() != null) {
            builder.minTokens(o.minTokens());
        }
        if (o.skipSpecialTokens() != null) {
            builder.skipSpecialTokens(o.skipSpecialTokens());
        }
        if (o.spacesBetweenSpecialTokens() != null) {
            builder.spacesBetweenSpecialTokens(o.spacesBetweenSpecialTokens());
        }
        if (o.truncatePromptTokens() != null) {
            builder.truncatePromptTokens(o.truncatePromptTokens());
        }
        if (o.promptLogprobs() != null) {
            builder.promptLogprobs(o.promptLogprobs());
        }
    }
}
