package qa.fanar.core.chat;

import java.util.Objects;

/**
 * One of {@code n} alternatives produced by a chat-completion request.
 *
 * @param finishReason why the model stopped; must not be {@code null}
 * @param index        position of this choice within {@link ChatResponse#choices()}, starting at 0
 * @param message      the assistant's message for this choice; must not be {@code null}
 * @param logprobs     per-token log probabilities; {@code null} unless the request set
 *                     {@code logprobs=true}
 */
public record ChatChoice(
        FinishReason finishReason,
        int index,
        ChatMessage message,
        ChoiceLogprobs logprobs
) {

    public ChatChoice {
        Objects.requireNonNull(finishReason, "finishReason");
        Objects.requireNonNull(message, "message");
    }
}
