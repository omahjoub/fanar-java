package qa.fanar.core.chat;

/**
 * Token-usage accounting for a single chat-completion response.
 *
 * <p>The three top-level counters are always present. The two details records are optional —
 * they carry finer-grained breakdowns (reasoning tokens for thinking models, cached prompt
 * tokens, audio tokens) when Fanar provides them; {@code null} otherwise. The two trailing
 * fields are Sadiq-specific: real Fanar-Sadiq responses carry retrieval-pipeline accounting
 * ({@code successful_requests} and {@code total_cost}) that the standard chat usage does not.
 * Both are {@code null} on non-Sadiq responses.</p>
 *
 * @param completionTokens          tokens generated for the completion
 * @param promptTokens              tokens consumed by the prompt
 * @param totalTokens               {@code completionTokens + promptTokens}
 * @param completionTokensDetails   completion-side breakdown; {@code null} if not provided
 * @param promptTokensDetails       prompt-side breakdown; {@code null} if not provided
 * @param successfulRequests        number of successful retrieval calls Sadiq made for this
 *                                  response; {@code null} on non-Sadiq responses
 * @param totalCost                 dollar cost of the Sadiq retrieval calls; {@code null} on
 *                                  non-Sadiq responses
 *
 * @author Oussama Mahjoub
 */
public record CompletionUsage(
        int completionTokens,
        int promptTokens,
        int totalTokens,
        CompletionTokensDetails completionTokensDetails,
        PromptTokensDetails promptTokensDetails,
        Integer successfulRequests,
        Double totalCost
) {
}
