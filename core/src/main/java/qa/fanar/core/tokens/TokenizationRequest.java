package qa.fanar.core.tokens;

import java.util.Objects;

import qa.fanar.core.chat.ChatModel;

/**
 * Request to tokenize a piece of content with a specific model — sent to
 * {@code POST /v1/tokens}.
 *
 * <p>{@code model} accepts any {@link ChatModel} value, but Fanar's tokenizer endpoint serves
 * only the three text-emitting variants: {@link ChatModel#FANAR_S_1_7B},
 * {@link ChatModel#FANAR_C_1_8_7B}, and {@link ChatModel#FANAR_C_2_27B}. Passing the router,
 * Sadiq, or a vision model surfaces a 422 from the server. The SDK does not narrow this
 * statically so callers can target new tokenizable models the day Fanar adds them.</p>
 *
 * @param content the text to tokenize; must not be {@code null}
 * @param model   the tokenizer model to use; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record TokenizationRequest(String content, ChatModel model) {

    public TokenizationRequest {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(model, "model");
    }

    /** Static factory mirroring {@link qa.fanar.core.chat.UserMessage#of(String)} for symmetry. */
    public static TokenizationRequest of(String content, ChatModel model) {
        return new TokenizationRequest(content, model);
    }
}
