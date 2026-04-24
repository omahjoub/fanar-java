package qa.fanar.core.chat;

/**
 * Token-usage accounting for a single chat-completion response.
 *
 * <p>The three top-level counters are always present. The two details records are optional —
 * they carry finer-grained breakdowns (reasoning tokens for thinking models, cached prompt
 * tokens, audio tokens) when Fanar provides them; {@code null} otherwise.</p>
 *
 * @param completionTokens          tokens generated for the completion
 * @param promptTokens              tokens consumed by the prompt
 * @param totalTokens               {@code completionTokens + promptTokens}
 * @param completionTokensDetails   completion-side breakdown; {@code null} if not provided
 * @param promptTokensDetails       prompt-side breakdown; {@code null} if not provided
 */
public record CompletionUsage(
        int completionTokens,
        int promptTokens,
        int totalTokens,
        CompletionTokensDetails completionTokensDetails,
        PromptTokensDetails promptTokensDetails
) {
}
