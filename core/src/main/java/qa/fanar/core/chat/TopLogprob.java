package qa.fanar.core.chat;

import java.util.List;
import java.util.Objects;

/**
 * One alternative token and its log probability, as recorded inside a {@link TokenLogprob}.
 *
 * @param token   the alternative token as a UTF-8 string; must not be {@code null}
 * @param bytes   raw byte values of the token; never {@code null}, may be empty
 * @param logprob the alternative's log probability (natural log)
 */
public record TopLogprob(
        String token,
        List<Integer> bytes,
        double logprob
) {

    public TopLogprob {
        Objects.requireNonNull(token, "token");
        bytes = bytes == null ? List.of() : List.copyOf(bytes);
    }
}
