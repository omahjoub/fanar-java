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
 * {@link FanarLlm}. A {@code null} field is not sent. Values are validated by
 * {@code qa.fanar.core.chat.ChatRequest} when the request is built, so an out-of-range value
 * surfaces as an {@code IllegalArgumentException} through the model's {@code Flowable}.
 *
 * <p>The knobs mirror the Fanar-only set of the Spring AI adapter's {@code FanarChatOptions}
 * (ADR-024): the Sadiq retrieval controls, thinking, and the sampling extras Fanar accepts beyond
 * what ADK's configuration carries.</p>
 *
 * @param unsupportedFeatures        what to do with features Fanar cannot honour; never {@code null}
 * @param persona                    Sadiq persona
 * @param madhab                     Sadiq schools of jurisprudence to answer from
 * @param enableThinking             emit the model's reasoning
 * @param restrictToIslamic          restrict Sadiq answers to Islamic sources
 * @param bookNames                  Sadiq book filter
 * @param preferredSources           Sadiq preferred sources
 * @param excludeSources             Sadiq excluded sources
 * @param filterSources              Sadiq source filter
 * @param logitBias                  token logit bias
 * @param minP                       minimum-p sampling
 * @param repetitionPenalty          repetition penalty
 * @param bestOf                     candidates generated server-side before choosing
 * @param lengthPenalty              length penalty
 * @param earlyStopping              stop beam search early
 * @param stopTokenIds               stop token ids
 * @param ignoreEos                  keep generating past end-of-sequence
 * @param minTokens                  minimum tokens to generate
 * @param skipSpecialTokens          strip special tokens from the output
 * @param spacesBetweenSpecialTokens add spaces between special tokens
 * @param truncatePromptTokens       truncate the prompt to this many tokens
 * @param promptLogprobs             prompt log-probabilities to return
 */
public record FanarLlmOptions(
        UnsupportedFeaturePolicy unsupportedFeatures,
        String persona,
        List<Madhab> madhab,
        Boolean enableThinking,
        Boolean restrictToIslamic,
        List<BookName> bookNames,
        List<Source> preferredSources,
        List<Source> excludeSources,
        List<Source> filterSources,
        Map<String, Double> logitBias,
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
        Integer promptLogprobs) {

    public FanarLlmOptions {
        Objects.requireNonNull(unsupportedFeatures, "unsupportedFeatures");
        madhab = copyOf(madhab);
        bookNames = copyOf(bookNames);
        preferredSources = copyOf(preferredSources);
        excludeSources = copyOf(excludeSources);
        filterSources = copyOf(filterSources);
        stopTokenIds = copyOf(stopTokenIds);
        logitBias = logitBias == null ? null : Map.copyOf(logitBias);
    }

    private static <T> List<T> copyOf(List<T> list) {
        return list == null ? null : List.copyOf(list);
    }

    /** No Fanar-only knobs and {@link UnsupportedFeaturePolicy#REJECT}. */
    public static FanarLlmOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder; every knob starts unset. */
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
            return new FanarLlmOptions(
                    unsupportedFeatures, persona, madhab, enableThinking, restrictToIslamic,
                    bookNames, preferredSources, excludeSources, filterSources, logitBias,
                    minP, repetitionPenalty, bestOf, lengthPenalty, earlyStopping, stopTokenIds,
                    ignoreEos, minTokens, skipSpecialTokens, spacesBetweenSpecialTokens,
                    truncatePromptTokens, promptLogprobs);
        }
    }
}
