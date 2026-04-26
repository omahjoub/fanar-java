package qa.fanar.core.chat;

import java.util.Objects;

/**
 * One of {@code n} alternatives produced by a chat-completion request.
 *
 * @param finishReason why the model stopped, normalised to a {@link FinishReason} enum value;
 *                     must not be {@code null}
 * @param index        position of this choice within {@link ChatResponse#choices()}, starting at 0
 * @param message      the assistant's message for this choice; must not be {@code null}
 * @param logprobs     per-token log probabilities; {@code null} unless the request set
 *                     {@code logprobs=true}
 * @param stopReason   raw stop-reason token Fanar emits alongside {@code finishReason}
 *                     (e.g. {@code "<end_of_turn>"}). Undocumented in the OpenAPI spec but
 *                     consistently present on real responses; useful for diagnosing why
 *                     generation halted at a finer granularity than the normalised enum.
 *                     {@code null} when the server omits it.
 *
 * @author Oussama Mahjoub
 */
public record ChatChoice(
        FinishReason finishReason,
        int index,
        ChatMessage message,
        ChoiceLogprobs logprobs,
        String stopReason
) {

    public ChatChoice {
        Objects.requireNonNull(finishReason, "finishReason");
        Objects.requireNonNull(message, "message");
    }
}
