package qa.fanar.core.chat;

/**
 * Completion-side token breakdown inside {@link CompletionUsage}.
 *
 * <p>All fields are nullable — Fanar sets only those relevant to the specific operation. In
 * particular, {@code reasoningTokens} is populated for thinking-enabled models and reflects
 * tokens spent on the model's internal reasoning (distinct from the final user-visible
 * completion).</p>
 *
 * @param acceptedPredictionTokens speculative-decoding tokens that matched and were accepted;
 *                                 {@code null} when not applicable
 * @param audioTokens              tokens representing audio segments in the completion;
 *                                 {@code null} for text-only completions
 * @param reasoningTokens          tokens the model spent on thinking (hidden from the user
 *                                 unless the thinking protocol is explicitly enabled);
 *                                 {@code null} when thinking was not used
 * @param rejectedPredictionTokens speculative-decoding tokens that were rejected;
 *                                 {@code null} when not applicable
 *
 * @author Oussama Mahjoub
 */
public record CompletionTokensDetails(
        Integer acceptedPredictionTokens,
        Integer audioTokens,
        Integer reasoningTokens,
        Integer rejectedPredictionTokens
) {
}
