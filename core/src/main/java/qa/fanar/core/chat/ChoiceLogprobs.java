package qa.fanar.core.chat;

import java.util.List;

/**
 * Per-token log probabilities for a {@link ChatChoice}.
 *
 * <p>Populated only when the request set {@code logprobs=true}. Both lists are defensively
 * copied and never returned {@code null} — an absent section is an empty list.</p>
 *
 * @param content token-level logprobs for the assistant's regular content; never {@code null},
 *                may be empty
 * @param refusal token-level logprobs for a refusal; never {@code null}, may be empty
 *
 * @author Oussama Mahjoub
 */
public record ChoiceLogprobs(
        List<TokenLogprob> content,
        List<TokenLogprob> refusal
) {

    public ChoiceLogprobs {
        content = content == null ? List.of() : List.copyOf(content);
        refusal = refusal == null ? List.of() : List.copyOf(refusal);
    }
}
