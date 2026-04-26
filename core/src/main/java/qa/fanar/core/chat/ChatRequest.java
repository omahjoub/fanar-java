package qa.fanar.core.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A chat-completion request sent to Fanar's {@code POST /v1/chat/completions} endpoint.
 *
 * <p>Construct via {@link #builder()} for the fluent form; every optional field defaults to
 * {@code null}, meaning "use the server default". The canonical constructor validates invariants
 * eagerly — range violations, required-field nulls, and oversized {@code stop} arrays throw at
 * construction, never at send time.</p>
 *
 * <p>Collections are defensively copied on construction and returned as unmodifiable views.
 * Validation follows ADR-015: required-field non-null, well-defined range checks on numeric
 * fields, and size limits where the wire spec documents them. Model-specific rules — for
 * example, {@code enableThinking} only applying to {@code Fanar-C-2-27B} — are Fanar's
 * responsibility; the SDK surfaces the server's rejection via the typed exception hierarchy.</p>
 *
 * <p>The wire field {@code stream} is <em>not</em> modelled here. Streaming vs. non-streaming is
 * a call-site choice on the domain facade (for example {@code client.chat().stream(request)}),
 * and the transport sets the wire field accordingly.</p>
 *
 * @author Oussama Mahjoub
 */
public record ChatRequest(
        // --- required
        List<Message> messages,
        ChatModel model,

        // --- common sampling
        Double temperature,
        Double topP,
        Integer maxTokens,
        Integer n,
        List<String> stop,
        Double frequencyPenalty,
        Double presencePenalty,
        Map<String, Double> logitBias,
        Boolean logprobs,
        Integer topLogprobs,

        // --- thinking
        Boolean enableThinking,

        // --- advanced sampling (vLLM-flavored knobs Fanar exposes)
        Integer topK,
        Double minP,
        Double repetitionPenalty,
        Integer bestOf,
        Double lengthPenalty,
        Boolean earlyStopping,
        List<Integer> stopTokenIds,
        Boolean ignoreEos,
        Integer minTokens,
        Boolean skipSpecialTokens,
        Boolean spacesBetweenSpecialTokens,
        Integer truncatePromptTokens,
        Integer promptLogprobs,

        // --- Fanar-Sadiq / Islamic RAG
        List<BookName> bookNames,
        List<Source> preferredSources,
        List<Source> excludeSources,
        List<Source> filterSources,
        Boolean restrictToIslamic
) {

    public ChatRequest {
        // Required fields
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        for (Message m : messages) {
            if (m == null) {
                throw new NullPointerException("messages contains a null element");
            }
        }
        messages = List.copyOf(messages);

        Objects.requireNonNull(model, "model");

        // Range validation on numeric fields (only when the caller supplied a value)
        requireInRange("temperature", temperature, 0.0, 2.0);
        requireInRange("topP", topP, 0.0, 1.0);
        requireMinInclusive("maxTokens", maxTokens, 1);
        requireMinInclusive("n", n, 1);
        requireInRange("frequencyPenalty", frequencyPenalty, -2.0, 2.0);
        requireInRange("presencePenalty", presencePenalty, -2.0, 2.0);
        requireInRangeInt("topLogprobs", topLogprobs, 0, 20);
        requireMinInclusive("minTokens", minTokens, 0);
        requireMinInclusive("topK", topK, 1);
        requireInRange("minP", minP, 0.0, 1.0);
        requireMinInclusive("bestOf", bestOf, 1);
        requireStrictlyPositive("repetitionPenalty", repetitionPenalty);
        requireMinInclusive("truncatePromptTokens", truncatePromptTokens, 1);
        requireMinInclusive("promptLogprobs", promptLogprobs, 0);

        // stop: max 4 entries per the Fanar spec
        if (stop != null) {
            stop = List.copyOf(stop);
            if (stop.size() > 4) {
                throw new IllegalArgumentException(
                        "stop supports at most 4 entries, got " + stop.size());
            }
        }

        // Defensive copies for nullable collections / maps
        logitBias = logitBias == null ? null : Map.copyOf(logitBias);
        stopTokenIds = stopTokenIds == null ? null : List.copyOf(stopTokenIds);
        bookNames = bookNames == null ? null : List.copyOf(bookNames);
        preferredSources = preferredSources == null ? null : List.copyOf(preferredSources);
        excludeSources = excludeSources == null ? null : List.copyOf(excludeSources);
        filterSources = filterSources == null ? null : List.copyOf(filterSources);
    }

    private static void requireInRange(String name, Double value, double min, double max) {
        if (value != null && (value < min || value > max)) {
            throw new IllegalArgumentException(
                    name + " must be in [" + min + ", " + max + "], got " + value);
        }
    }

    private static void requireInRangeInt(String name, Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            throw new IllegalArgumentException(
                    name + " must be in [" + min + ", " + max + "], got " + value);
        }
    }

    private static void requireMinInclusive(String name, Integer value, int min) {
        if (value != null && value < min) {
            throw new IllegalArgumentException(
                    name + " must be >= " + min + ", got " + value);
        }
    }

    private static void requireStrictlyPositive(String name, Double value) {
        if (value != null && value <= 0.0) {
            throw new IllegalArgumentException(
                    name + " must be > 0, got " + value);
        }
    }

    /** Start a fresh builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link ChatRequest}. Every optional field defaults to {@code null}.
     *
     * <p>{@link #build()} delegates to the record's canonical constructor and therefore runs
     * the same validation — misconfigured builders throw at {@link #build()}, never later.</p>
     */
    public static final class Builder {

        private final List<Message> messages = new ArrayList<>();
        private ChatModel model;
        private Double temperature;
        private Double topP;
        private Integer maxTokens;
        private Integer n;
        private List<String> stop;
        private Double frequencyPenalty;
        private Double presencePenalty;
        private Map<String, Double> logitBias;
        private Boolean logprobs;
        private Integer topLogprobs;
        private Boolean enableThinking;
        private Integer topK;
        private Double minP;
        private Double repetitionPenalty;
        private Integer bestOf;
        private Double lengthPenalty;
        private Boolean earlyStopping;
        private List<Integer> stopTokenIds;
        private Boolean ignoreEos;
        private Integer minTokens;
        private Boolean skipSpecialTokens;
        private Boolean spacesBetweenSpecialTokens;
        private Integer truncatePromptTokens;
        private Integer promptLogprobs;
        private List<BookName> bookNames;
        private List<Source> preferredSources;
        private List<Source> excludeSources;
        private List<Source> filterSources;
        private Boolean restrictToIslamic;

        private Builder() {
            // use ChatRequest.builder()
        }

        /** Append one message to the conversation. At least one message is required to {@link #build()}. */
        public Builder addMessage(Message message) {
            Objects.requireNonNull(message, "message");
            this.messages.add(message);
            return this;
        }

        /** Replace the current conversation with {@code messages}. Caller's list is defensively copied. */
        public Builder messages(List<Message> messages) {
            Objects.requireNonNull(messages, "messages");
            this.messages.clear();
            for (Message m : messages) {
                Objects.requireNonNull(m, "messages contains a null element");
                this.messages.add(m);
            }
            return this;
        }

        public Builder model(ChatModel model) { this.model = model; return this; }

        public Builder temperature(Double temperature) { this.temperature = temperature; return this; }
        public Builder topP(Double topP) { this.topP = topP; return this; }
        public Builder maxTokens(Integer maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder n(Integer n) { this.n = n; return this; }
        public Builder stop(List<String> stop) { this.stop = stop; return this; }
        public Builder frequencyPenalty(Double frequencyPenalty) { this.frequencyPenalty = frequencyPenalty; return this; }
        public Builder presencePenalty(Double presencePenalty) { this.presencePenalty = presencePenalty; return this; }
        public Builder logitBias(Map<String, Double> logitBias) { this.logitBias = logitBias; return this; }
        public Builder logprobs(Boolean logprobs) { this.logprobs = logprobs; return this; }
        public Builder topLogprobs(Integer topLogprobs) { this.topLogprobs = topLogprobs; return this; }

        public Builder enableThinking(Boolean enableThinking) { this.enableThinking = enableThinking; return this; }

        public Builder topK(Integer topK) { this.topK = topK; return this; }
        public Builder minP(Double minP) { this.minP = minP; return this; }
        public Builder repetitionPenalty(Double repetitionPenalty) { this.repetitionPenalty = repetitionPenalty; return this; }
        public Builder bestOf(Integer bestOf) { this.bestOf = bestOf; return this; }
        public Builder lengthPenalty(Double lengthPenalty) { this.lengthPenalty = lengthPenalty; return this; }
        public Builder earlyStopping(Boolean earlyStopping) { this.earlyStopping = earlyStopping; return this; }
        public Builder stopTokenIds(List<Integer> stopTokenIds) { this.stopTokenIds = stopTokenIds; return this; }
        public Builder ignoreEos(Boolean ignoreEos) { this.ignoreEos = ignoreEos; return this; }
        public Builder minTokens(Integer minTokens) { this.minTokens = minTokens; return this; }
        public Builder skipSpecialTokens(Boolean skipSpecialTokens) { this.skipSpecialTokens = skipSpecialTokens; return this; }
        public Builder spacesBetweenSpecialTokens(Boolean spacesBetweenSpecialTokens) { this.spacesBetweenSpecialTokens = spacesBetweenSpecialTokens; return this; }
        public Builder truncatePromptTokens(Integer truncatePromptTokens) { this.truncatePromptTokens = truncatePromptTokens; return this; }
        public Builder promptLogprobs(Integer promptLogprobs) { this.promptLogprobs = promptLogprobs; return this; }

        public Builder bookNames(List<BookName> bookNames) { this.bookNames = bookNames; return this; }
        public Builder preferredSources(List<Source> preferredSources) { this.preferredSources = preferredSources; return this; }
        public Builder excludeSources(List<Source> excludeSources) { this.excludeSources = excludeSources; return this; }
        public Builder filterSources(List<Source> filterSources) { this.filterSources = filterSources; return this; }
        public Builder restrictToIslamic(Boolean restrictToIslamic) { this.restrictToIslamic = restrictToIslamic; return this; }

        /**
         * Validate and build the {@link ChatRequest}.
         *
         * @throws IllegalArgumentException if any validation rule is violated
         * @throws NullPointerException     if a required field is missing
         */
        public ChatRequest build() {
            return new ChatRequest(
                    messages, model,
                    temperature, topP, maxTokens, n, stop,
                    frequencyPenalty, presencePenalty, logitBias, logprobs, topLogprobs,
                    enableThinking,
                    topK, minP, repetitionPenalty, bestOf, lengthPenalty, earlyStopping,
                    stopTokenIds, ignoreEos, minTokens, skipSpecialTokens,
                    spacesBetweenSpecialTokens, truncatePromptTokens, promptLogprobs,
                    bookNames, preferredSources, excludeSources, filterSources, restrictToIslamic);
        }
    }
}
