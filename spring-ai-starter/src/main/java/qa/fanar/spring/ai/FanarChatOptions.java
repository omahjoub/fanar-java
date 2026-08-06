package qa.fanar.spring.ai;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptionsBuilder;

import qa.fanar.core.chat.BookName;
import qa.fanar.core.chat.Madhab;
import qa.fanar.core.chat.Source;

/**
 * Fanar-specific {@link ChatOptions}: the standard portable knobs plus every Fanar chat
 * parameter that portable options cannot carry — Islamic-RAG scoping, persona, madhab,
 * thinking mode, and the vLLM-flavoured sampling knobs.
 *
 * <p>Pass an instance as the prompt's options ({@code ChatClient.prompt().options(...)} or
 * {@code new Prompt(messages, options)}); {@code FanarChatModel} maps the portable getters like
 * any {@link ChatOptions} and additionally applies the Fanar extras. Any other
 * {@link ChatOptions} implementation keeps working — the extras are then simply unset
 * (ADR-024).</p>
 *
 * <p>{@link Builder} extends Spring AI's {@link DefaultChatOptionsBuilder}, so
 * {@link #mutate()} round-trips <em>all</em> fields — including the Fanar extras — through the
 * {@code ChatClient} pipeline (which rebuilds request options via {@code mutate()}), and
 * {@link Builder#combineWith(ChatOptions.Builder)} merges extras when combining two Fanar
 * builders (non-null values from the other builder win; the portable fields follow Spring AI's
 * own merge rules).</p>
 *
 * <p>Instances are immutable; collections are defensively copied at build time. Field semantics
 * and validation mirror {@code qa.fanar.core.chat.ChatRequest} — validation happens when the
 * request is built, not here.</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarChatOptions implements ChatOptions {

    // --- portable (ChatOptions) ---
    private final String model;
    private final Double temperature;
    private final Double topP;
    private final Integer topK;
    private final Integer maxTokens;
    private final Double frequencyPenalty;
    private final Double presencePenalty;
    private final List<String> stopSequences;

    // --- Fanar extras ---
    private final String persona;
    private final List<Madhab> madhab;
    private final Boolean enableThinking;
    private final Boolean restrictToIslamic;
    private final List<BookName> bookNames;
    private final List<Source> preferredSources;
    private final List<Source> excludeSources;
    private final List<Source> filterSources;
    private final Map<String, Double> logitBias;
    private final Boolean logprobs;
    private final Integer topLogprobs;
    private final Integer n;
    private final Double minP;
    private final Double repetitionPenalty;
    private final Integer bestOf;
    private final Double lengthPenalty;
    private final Boolean earlyStopping;
    private final List<Integer> stopTokenIds;
    private final Boolean ignoreEos;
    private final Integer minTokens;
    private final Boolean skipSpecialTokens;
    private final Boolean spacesBetweenSpecialTokens;
    private final Integer truncatePromptTokens;
    private final Integer promptLogprobs;

    private FanarChatOptions(
            Builder b,
            String model, Double temperature, Double topP, Integer topK, Integer maxTokens,
            Double frequencyPenalty, Double presencePenalty, List<String> stopSequences) {
        this.model = model;
        this.temperature = temperature;
        this.topP = topP;
        this.topK = topK;
        this.maxTokens = maxTokens;
        this.frequencyPenalty = frequencyPenalty;
        this.presencePenalty = presencePenalty;
        this.stopSequences = stopSequences == null ? null : List.copyOf(stopSequences);
        this.persona = b.persona;
        this.madhab = b.madhab == null ? null : List.copyOf(b.madhab);
        this.enableThinking = b.enableThinking;
        this.restrictToIslamic = b.restrictToIslamic;
        this.bookNames = b.bookNames == null ? null : List.copyOf(b.bookNames);
        this.preferredSources = b.preferredSources == null ? null : List.copyOf(b.preferredSources);
        this.excludeSources = b.excludeSources == null ? null : List.copyOf(b.excludeSources);
        this.filterSources = b.filterSources == null ? null : List.copyOf(b.filterSources);
        this.logitBias = b.logitBias == null ? null : Map.copyOf(b.logitBias);
        this.logprobs = b.logprobs;
        this.topLogprobs = b.topLogprobs;
        this.n = b.n;
        this.minP = b.minP;
        this.repetitionPenalty = b.repetitionPenalty;
        this.bestOf = b.bestOf;
        this.lengthPenalty = b.lengthPenalty;
        this.earlyStopping = b.earlyStopping;
        this.stopTokenIds = b.stopTokenIds == null ? null : List.copyOf(b.stopTokenIds);
        this.ignoreEos = b.ignoreEos;
        this.minTokens = b.minTokens;
        this.skipSpecialTokens = b.skipSpecialTokens;
        this.spacesBetweenSpecialTokens = b.spacesBetweenSpecialTokens;
        this.truncatePromptTokens = b.truncatePromptTokens;
        this.promptLogprobs = b.promptLogprobs;
    }

    /** Start a fresh builder. */
    public static Builder builder() {
        return new Builder();
    }

    // --- portable getters (ChatOptions) ---

    @Override public String getModel() { return model; }
    @Override public Double getTemperature() { return temperature; }
    @Override public Double getTopP() { return topP; }
    @Override public Integer getTopK() { return topK; }
    @Override public Integer getMaxTokens() { return maxTokens; }
    @Override public Double getFrequencyPenalty() { return frequencyPenalty; }
    @Override public Double getPresencePenalty() { return presencePenalty; }
    @Override public List<String> getStopSequences() { return stopSequences; }

    /** Full-fidelity mutation: the returned builder carries the Fanar extras too. */
    @Override
    public ChatOptions.Builder<?> mutate() {
        return toBuilder();
    }

    /** A fresh {@link Builder} pre-populated with every field of this instance. */
    public Builder toBuilder() {
        Builder b = new Builder()
                .persona(persona)
                .madhab(madhab)
                .enableThinking(enableThinking)
                .restrictToIslamic(restrictToIslamic)
                .bookNames(bookNames)
                .preferredSources(preferredSources)
                .excludeSources(excludeSources)
                .filterSources(filterSources)
                .logitBias(logitBias)
                .logprobs(logprobs)
                .topLogprobs(topLogprobs)
                .n(n)
                .minP(minP)
                .repetitionPenalty(repetitionPenalty)
                .bestOf(bestOf)
                .lengthPenalty(lengthPenalty)
                .earlyStopping(earlyStopping)
                .stopTokenIds(stopTokenIds)
                .ignoreEos(ignoreEos)
                .minTokens(minTokens)
                .skipSpecialTokens(skipSpecialTokens)
                .spacesBetweenSpecialTokens(spacesBetweenSpecialTokens)
                .truncatePromptTokens(truncatePromptTokens)
                .promptLogprobs(promptLogprobs);
        return b.model(model)
                .temperature(temperature)
                .topP(topP)
                .topK(topK)
                .maxTokens(maxTokens)
                .frequencyPenalty(frequencyPenalty)
                .presencePenalty(presencePenalty)
                .stopSequences(stopSequences);
    }

    // --- Fanar extras ---

    /** Custom assistant persona ({@code Fanar-Sadiq} only), or {@code null}. */
    public String getPersona() { return persona; }

    /** Madhab filter for {@code Fanar-Sadiq-2}, or {@code null}. */
    public List<Madhab> getMadhab() { return madhab; }

    /** Thinking-mode flag ({@code Fanar-C-2-27B}), or {@code null}. */
    public Boolean getEnableThinking() { return enableThinking; }

    /** Server-side non-Islamic prompt rejection ({@code Fanar-Sadiq}), or {@code null}. */
    public Boolean getRestrictToIslamic() { return restrictToIslamic; }

    /** Islamic-RAG retrieval scope: book filter, or {@code null}. */
    public List<BookName> getBookNames() { return bookNames; }

    /** Islamic-RAG retrieval scope: preferred corpora, or {@code null}. */
    public List<Source> getPreferredSources() { return preferredSources; }

    /** Islamic-RAG retrieval scope: excluded corpora, or {@code null}. */
    public List<Source> getExcludeSources() { return excludeSources; }

    /** Islamic-RAG retrieval scope: hard corpus filter, or {@code null}. */
    public List<Source> getFilterSources() { return filterSources; }

    /** Token-id → bias map, or {@code null}. */
    public Map<String, Double> getLogitBias() { return logitBias; }

    /** Return log-probabilities, or {@code null}. */
    public Boolean getLogprobs() { return logprobs; }

    /** How many top log-probabilities per token, or {@code null}. */
    public Integer getTopLogprobs() { return topLogprobs; }

    /** Number of completions to generate, or {@code null}. */
    public Integer getN() { return n; }

    /** Min-p sampling, or {@code null}. */
    public Double getMinP() { return minP; }

    /** Repetition penalty, or {@code null}. */
    public Double getRepetitionPenalty() { return repetitionPenalty; }

    /** Beam-search candidate count, or {@code null}. */
    public Integer getBestOf() { return bestOf; }

    /** Beam-search length penalty, or {@code null}. */
    public Double getLengthPenalty() { return lengthPenalty; }

    /** Beam-search early stopping, or {@code null}. */
    public Boolean getEarlyStopping() { return earlyStopping; }

    /** Stop token ids, or {@code null}. */
    public List<Integer> getStopTokenIds() { return stopTokenIds; }

    /** Ignore end-of-sequence token, or {@code null}. */
    public Boolean getIgnoreEos() { return ignoreEos; }

    /** Minimum tokens to generate, or {@code null}. */
    public Integer getMinTokens() { return minTokens; }

    /** Skip special tokens in output, or {@code null}. */
    public Boolean getSkipSpecialTokens() { return skipSpecialTokens; }

    /** Spaces between special tokens, or {@code null}. */
    public Boolean getSpacesBetweenSpecialTokens() { return spacesBetweenSpecialTokens; }

    /** Prompt truncation limit, or {@code null}. */
    public Integer getTruncatePromptTokens() { return truncatePromptTokens; }

    /** Prompt log-probabilities, or {@code null}. */
    public Integer getPromptLogprobs() { return promptLogprobs; }

    /**
     * Fluent builder; every field defaults to {@code null} ("use the server default").
     *
     * <p>Extends {@link DefaultChatOptionsBuilder} so Spring AI treats it as a first-class
     * {@link ChatOptions.Builder}: the portable setters, {@code clone()}, and the portable half
     * of {@code combineWith(...)} are inherited. The override below additionally merges the
     * Fanar extras when the other builder is also a {@code FanarChatOptions.Builder} —
     * non-null scalar and collection values from {@code other} replace this builder's values
     * (collections replace rather than concatenate: they are filters, and appending two
     * filters is not a meaningful union).</p>
     */
    public static final class Builder extends DefaultChatOptionsBuilder<Builder> {

        private String persona;
        private List<Madhab> madhab;
        private Boolean enableThinking;
        private Boolean restrictToIslamic;
        private List<BookName> bookNames;
        private List<Source> preferredSources;
        private List<Source> excludeSources;
        private List<Source> filterSources;
        private Map<String, Double> logitBias;
        private Boolean logprobs;
        private Integer topLogprobs;
        private Integer n;
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

        private Builder() {
            // use FanarChatOptions.builder()
        }

        public Builder persona(String persona) { this.persona = persona; return this; }
        public Builder madhab(List<Madhab> madhab) { this.madhab = madhab; return this; }
        public Builder enableThinking(Boolean enableThinking) { this.enableThinking = enableThinking; return this; }
        public Builder restrictToIslamic(Boolean restrictToIslamic) { this.restrictToIslamic = restrictToIslamic; return this; }
        public Builder bookNames(List<BookName> bookNames) { this.bookNames = bookNames; return this; }
        public Builder preferredSources(List<Source> preferredSources) { this.preferredSources = preferredSources; return this; }
        public Builder excludeSources(List<Source> excludeSources) { this.excludeSources = excludeSources; return this; }
        public Builder filterSources(List<Source> filterSources) { this.filterSources = filterSources; return this; }
        public Builder logitBias(Map<String, Double> logitBias) { this.logitBias = logitBias; return this; }
        public Builder logprobs(Boolean logprobs) { this.logprobs = logprobs; return this; }
        public Builder topLogprobs(Integer topLogprobs) { this.topLogprobs = topLogprobs; return this; }
        public Builder n(Integer n) { this.n = n; return this; }
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

        @Override
        public Builder combineWith(ChatOptions.Builder<?> other) {
            super.combineWith(other);
            if (other instanceof Builder that) {
                if (that.persona != null) {
                    this.persona = that.persona;
                }
                if (that.madhab != null) {
                    this.madhab = that.madhab;
                }
                if (that.enableThinking != null) {
                    this.enableThinking = that.enableThinking;
                }
                if (that.restrictToIslamic != null) {
                    this.restrictToIslamic = that.restrictToIslamic;
                }
                if (that.bookNames != null) {
                    this.bookNames = that.bookNames;
                }
                if (that.preferredSources != null) {
                    this.preferredSources = that.preferredSources;
                }
                if (that.excludeSources != null) {
                    this.excludeSources = that.excludeSources;
                }
                if (that.filterSources != null) {
                    this.filterSources = that.filterSources;
                }
                if (that.logitBias != null) {
                    this.logitBias = that.logitBias;
                }
                if (that.logprobs != null) {
                    this.logprobs = that.logprobs;
                }
                if (that.topLogprobs != null) {
                    this.topLogprobs = that.topLogprobs;
                }
                if (that.n != null) {
                    this.n = that.n;
                }
                if (that.minP != null) {
                    this.minP = that.minP;
                }
                if (that.repetitionPenalty != null) {
                    this.repetitionPenalty = that.repetitionPenalty;
                }
                if (that.bestOf != null) {
                    this.bestOf = that.bestOf;
                }
                if (that.lengthPenalty != null) {
                    this.lengthPenalty = that.lengthPenalty;
                }
                if (that.earlyStopping != null) {
                    this.earlyStopping = that.earlyStopping;
                }
                if (that.stopTokenIds != null) {
                    this.stopTokenIds = that.stopTokenIds;
                }
                if (that.ignoreEos != null) {
                    this.ignoreEos = that.ignoreEos;
                }
                if (that.minTokens != null) {
                    this.minTokens = that.minTokens;
                }
                if (that.skipSpecialTokens != null) {
                    this.skipSpecialTokens = that.skipSpecialTokens;
                }
                if (that.spacesBetweenSpecialTokens != null) {
                    this.spacesBetweenSpecialTokens = that.spacesBetweenSpecialTokens;
                }
                if (that.truncatePromptTokens != null) {
                    this.truncatePromptTokens = that.truncatePromptTokens;
                }
                if (that.promptLogprobs != null) {
                    this.promptLogprobs = that.promptLogprobs;
                }
            }
            return self();
        }

        @Override
        public FanarChatOptions build() {
            return new FanarChatOptions(this,
                    this.model, this.temperature, this.topP, this.topK, this.maxTokens,
                    this.frequencyPenalty, this.presencePenalty, this.stopSequences);
        }
    }
}
