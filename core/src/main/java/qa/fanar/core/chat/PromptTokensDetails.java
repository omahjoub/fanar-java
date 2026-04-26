package qa.fanar.core.chat;

/**
 * Prompt-side token breakdown inside {@link CompletionUsage}.
 *
 * <p>Fields are nullable; Fanar populates what applies.</p>
 *
 * @param audioTokens   tokens representing audio segments in the prompt; {@code null} when the
 *                      prompt had no audio
 * @param cachedTokens  prompt tokens served from Fanar's prompt cache; {@code null} when the
 *                      prompt had no cached portion
 *
 * @author Oussama Mahjoub
 */
public record PromptTokensDetails(
        Integer audioTokens,
        Integer cachedTokens
) {
}
