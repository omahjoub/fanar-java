package qa.fanar.core.sadiq;

import java.util.Objects;

import qa.fanar.core.chat.ChatModel;

/**
 * A research topic for {@code POST /v1/sadiq/deep-research}: the server retrieves passages from
 * the Islamic knowledge base, synthesises them over one or more research passes and returns a
 * hierarchical, cited {@link DeepResearchReport}.
 *
 * <p>{@code model} is a {@link ChatModel}: the endpoint's spec schema lists only
 * {@code Fanar-Sadiq-2}, already a chat model, so this follows the choice
 * {@link SadiqValidationRequest} and {@code TokenizationRequest} make (ADR-015). The SDK does not
 * narrow it statically; a model the endpoint does not serve surfaces as a server error.</p>
 *
 * <p>The wire fields {@code stream} and {@code background} are deliberately not modelled: whether
 * the report arrives as a stream, as one value or through a background job is the call-site
 * method's choice on {@link SadiqClient} (ADR-023, ADR-032). A run takes minutes — see
 * {@link DeepResearchDepth} — and the endpoint has a small daily quota that each admitted run
 * spends, so the SDK never retries a call that starts one (ADR-031).</p>
 *
 * <p>The same record comes back as {@link DeepResearchJob#request()}, with the server's defaults
 * for depth and web search filled in.</p>
 *
 * @param model     the model to research with; must not be {@code null}
 * @param input     the research topic or question; must not be {@code null}
 * @param depth     how far the run goes; {@code null} lets the server choose
 *                  ({@link DeepResearchDepth#STANDARD})
 * @param webSearch whether to augment the knowledge base with live web sources; {@code null}
 *                  lets the server choose (off)
 *
 * @author Oussama Mahjoub
 */
public record DeepResearchRequest(ChatModel model, String input, DeepResearchDepth depth, Boolean webSearch) {

    public DeepResearchRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(input, "input");
    }

    /** A request with the server's defaults for depth and web search. */
    public static DeepResearchRequest of(ChatModel model, String input) {
        return new DeepResearchRequest(model, input, null, null);
    }
}
