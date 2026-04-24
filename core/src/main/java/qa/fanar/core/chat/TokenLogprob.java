package qa.fanar.core.chat;

import java.util.List;
import java.util.Objects;

/**
 * Log probability for a single token emitted by the model, plus the top alternatives the model
 * considered at that position.
 *
 * @param token        the chosen token as a UTF-8 string; must not be {@code null}
 * @param bytes        raw byte values of the token; never {@code null}, may be empty
 * @param logprob      the chosen token's log probability (natural log)
 * @param topLogprobs  the {@code top_logprobs} most likely alternative tokens at this position;
 *                     never {@code null}, may be empty
 */
public record TokenLogprob(
        String token,
        List<Integer> bytes,
        double logprob,
        List<TopLogprob> topLogprobs
) {

    public TokenLogprob {
        Objects.requireNonNull(token, "token");
        bytes = bytes == null ? List.of() : List.copyOf(bytes);
        topLogprobs = topLogprobs == null ? List.of() : List.copyOf(topLogprobs);
    }
}
