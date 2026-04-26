package qa.fanar.core.moderations;

import java.util.Objects;

/**
 * Prompt/response pair to be scored by a Fanar moderation model — sent to
 * {@code POST /v1/moderations}.
 *
 * <p>The endpoint scores both halves of the conversation: {@code prompt} (the user input) and
 * {@code response} (what the model said back). The returned {@link SafetyFilterResponse}
 * carries a generic safety score plus a cultural-awareness score.</p>
 *
 * @param model    the moderation model to use; must not be {@code null}
 * @param prompt   the user's prompt; must not be {@code null}
 * @param response the model's response to evaluate alongside the prompt; must not be {@code null}
 *
 * @author Oussama Mahjoub
 */
public record SafetyFilterRequest(ModerationModel model, String prompt, String response) {

    public SafetyFilterRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(response, "response");
    }

    /** Static factory — argument order matches the JSON wire shape. */
    public static SafetyFilterRequest of(ModerationModel model, String prompt, String response) {
        return new SafetyFilterRequest(model, prompt, response);
    }
}
