package qa.fanar.adk;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import qa.fanar.core.chat.BookName;
import qa.fanar.core.chat.Madhab;
import qa.fanar.core.chat.Source;

/**
 * Fanar-only request knobs for one {@link FanarLlm} instance (ADR-030), plus the policy for
 * features Fanar cannot honour. ADK's {@code GenerateContentConfig} has no extension seam, so these
 * are set once per model instance; an agent that needs different values gets its own
 * {@link FanarLlm}. An unset knob is not sent. Values are validated by
 * {@code qa.fanar.core.chat.ChatRequest} when the request is built, so an out-of-range value
 * surfaces as an {@code IllegalArgumentException} through the model's {@code Flowable}.
 *
 * <p>The knobs mirror the Fanar-only set of the Spring AI adapter's {@code FanarChatOptions}
 * (ADR-024): the Sadiq retrieval controls, thinking, and the sampling extras Fanar accepts beyond
 * what ADK's configuration carries. Like that class this is a builder-built value, not a record,
 * so a knob Fanar adds later is an added setter rather than a changed constructor.</p>
 */
public final class FanarLlmOptions {

    private final UnsupportedFeaturePolicy unsupportedFeatures;
    private final String persona;
    private final List<Madhab> madhab;
    private final Boolean enableThinking;
    private final Boolean restrictToIslamic;
    private final List<BookName> bookNames;
    private final List<Source> preferredSources;
    private final List<Source> excludeSources;
    private final List<Source> filterSources;
    private final Map<String, Double> logitBias;
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

    private FanarLlmOptions(Builder b) {
        this.unsupportedFeatures = Objects.requireNonNull(b.unsupportedFeatures, "unsupportedFeatures");
        this.persona = b.persona;
        this.madhab = copyOf(b.madhab);
        this.enableThinking = b.enableThinking;
        this.restrictToIslamic = b.restrictToIslamic;
        this.bookNames = copyOf(b.bookNames);
        this.preferredSources = copyOf(b.preferredSources);
        this.excludeSources = copyOf(b.excludeSources);
        this.filterSources = copyOf(b.filterSources);
        this.logitBias = b.logitBias == null ? null : Map.copyOf(b.logitBias);
        this.minP = b.minP;
        this.repetitionPenalty = b.repetitionPenalty;
        this.bestOf = b.bestOf;
        this.lengthPenalty = b.lengthPenalty;
        this.earlyStopping = b.earlyStopping;
        this.stopTokenIds = copyOf(b.stopTokenIds);
        this.ignoreEos = b.ignoreEos;
        this.minTokens = b.minTokens;
        this.skipSpecialTokens = b.skipSpecialTokens;
        this.spacesBetweenSpecialTokens = b.spacesBetweenSpecialTokens;
        this.truncatePromptTokens = b.truncatePromptTokens;
        this.promptLogprobs = b.promptLogprobs;
    }

    private static <T> List<T> copyOf(List<T> list) {
        return list == null ? null : List.copyOf(list);
    }

    /** No Fanar-only knobs and {@link UnsupportedFeaturePolicy#REJECT}. */
    public static FanarLlmOptions defaults() {
        return builder().build();
    }

    /** A builder with every knob unset and the policy at {@link UnsupportedFeaturePolicy#REJECT}. */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder pre-filled with this value's knobs, for deriving a variant. */
    public Builder toBuilder() {
        return new Builder()
                .unsupportedFeatures(unsupportedFeatures).persona(persona).madhab(madhab)
                .enableThinking(enableThinking).restrictToIslamic(restrictToIslamic).bookNames(bookNames)
                .preferredSources(preferredSources).excludeSources(excludeSources).filterSources(filterSources)
                .logitBias(logitBias).minP(minP).repetitionPenalty(repetitionPenalty).bestOf(bestOf)
                .lengthPenalty(lengthPenalty).earlyStopping(earlyStopping).stopTokenIds(stopTokenIds)
                .ignoreEos(ignoreEos).minTokens(minTokens).skipSpecialTokens(skipSpecialTokens)
                .spacesBetweenSpecialTokens(spacesBetweenSpecialTokens).truncatePromptTokens(truncatePromptTokens)
                .promptLogprobs(promptLogprobs);
    }

    /** What to do with features Fanar cannot honour; never {@code null}. */
    public UnsupportedFeaturePolicy unsupportedFeatures() { return unsupportedFeatures; }
    /** Sadiq persona. */
    public String persona() { return persona; }
    /** Sadiq schools of jurisprudence to answer from. */
    public List<Madhab> madhab() { return madhab; }
    /** Emit the model's reasoning (Fanar returns it inline as a {@code <think>} block). */
    public Boolean enableThinking() { return enableThinking; }
    /** Restrict Sadiq answers to Islamic sources. */
    public Boolean restrictToIslamic() { return restrictToIslamic; }
    /** Sadiq book filter. */
    public List<BookName> bookNames() { return bookNames; }
    /** Sadiq preferred sources. */
    public List<Source> preferredSources() { return preferredSources; }
    /** Sadiq excluded sources. */
    public List<Source> excludeSources() { return excludeSources; }
    /** Sadiq source filter. */
    public List<Source> filterSources() { return filterSources; }
    /** Token logit bias. */
    public Map<String, Double> logitBias() { return logitBias; }
    /** Minimum-p sampling. */
    public Double minP() { return minP; }
    /** Repetition penalty. */
    public Double repetitionPenalty() { return repetitionPenalty; }
    /** Candidates generated server-side before choosing. */
    public Integer bestOf() { return bestOf; }
    /** Length penalty. */
    public Double lengthPenalty() { return lengthPenalty; }
    /** Stop beam search early. */
    public Boolean earlyStopping() { return earlyStopping; }
    /** Stop token ids. */
    public List<Integer> stopTokenIds() { return stopTokenIds; }
    /** Keep generating past end-of-sequence. */
    public Boolean ignoreEos() { return ignoreEos; }
    /** Minimum tokens to generate. */
    public Integer minTokens() { return minTokens; }
    /** Strip special tokens from the output. */
    public Boolean skipSpecialTokens() { return skipSpecialTokens; }
    /** Add spaces between special tokens. */
    public Boolean spacesBetweenSpecialTokens() { return spacesBetweenSpecialTokens; }
    /** Truncate the prompt to this many tokens. */
    public Integer truncatePromptTokens() { return truncatePromptTokens; }
    /** Prompt log-probabilities to return. */
    public Integer promptLogprobs() { return promptLogprobs; }

    /** Fluent builder; each setter mirrors the accessor of the same name and accepts {@code null} to unset. */
    public static final class Builder {

        private UnsupportedFeaturePolicy unsupportedFeatures = UnsupportedFeaturePolicy.REJECT;
        private String persona;
        private List<Madhab> madhab;
        private Boolean enableThinking;
        private Boolean restrictToIslamic;
        private List<BookName> bookNames;
        private List<Source> preferredSources;
        private List<Source> excludeSources;
        private List<Source> filterSources;
        private Map<String, Double> logitBias;
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
            // use FanarLlmOptions.builder()
        }

        public Builder unsupportedFeatures(UnsupportedFeaturePolicy policy) { this.unsupportedFeatures = policy; return this; }
        public Builder persona(String persona) { this.persona = persona; return this; }
        public Builder madhab(List<Madhab> madhab) { this.madhab = madhab; return this; }
        public Builder enableThinking(Boolean enableThinking) { this.enableThinking = enableThinking; return this; }
        public Builder restrictToIslamic(Boolean restrictToIslamic) { this.restrictToIslamic = restrictToIslamic; return this; }
        public Builder bookNames(List<BookName> bookNames) { this.bookNames = bookNames; return this; }
        public Builder preferredSources(List<Source> preferredSources) { this.preferredSources = preferredSources; return this; }
        public Builder excludeSources(List<Source> excludeSources) { this.excludeSources = excludeSources; return this; }
        public Builder filterSources(List<Source> filterSources) { this.filterSources = filterSources; return this; }
        public Builder logitBias(Map<String, Double> logitBias) { this.logitBias = logitBias; return this; }
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

        /**
         * @throws NullPointerException if the policy was set to {@code null}
         */
        public FanarLlmOptions build() {
            return new FanarLlmOptions(this);
        }
    }
}
