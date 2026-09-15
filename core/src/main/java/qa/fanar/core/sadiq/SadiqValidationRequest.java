package qa.fanar.core.sadiq;

import java.util.Objects;

import qa.fanar.core.chat.ChatModel;

/**
 * Text to be checked for Qur'anic verses and hadith quotations — sent to
 * {@code POST /v1/sadiq/validate}.
 *
 * <p>{@code model} is a {@link ChatModel} rather than a dedicated type: the endpoint's spec schema
 * lists only {@code Fanar-Sadiq-2}, which is already a chat model, so this follows the same choice
 * {@code TokenizationRequest} makes. The SDK does not narrow it statically, so callers can target a
 * new validating model the day Fanar ships one. Passing a model the endpoint does not serve
 * surfaces an error from the server, not from the SDK.</p>
 *
 * @param model the model to validate with; must not be {@code null}
 * @param text  the input text to check for quotations; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record SadiqValidationRequest(ChatModel model, String text) {

    public SadiqValidationRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(text, "text");
    }

    /** Static factory — argument order matches the JSON wire shape. */
    public static SadiqValidationRequest of(ChatModel model, String text) {
        return new SadiqValidationRequest(model, text);
    }
}
