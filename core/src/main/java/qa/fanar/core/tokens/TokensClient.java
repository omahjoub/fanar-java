package qa.fanar.core.tokens;

import java.util.concurrent.CompletableFuture;

/**
 * Domain facade for the {@code /v1/tokens} endpoint. Returned by {@code FanarClient.tokens()}.
 *
 * <p>Two variants — same call, different waiting strategies:</p>
 * <ul>
 *   <li>{@link #count(TokenizationRequest)} — blocking. On Java 21 virtual threads the block
 *       doesn't pin a carrier, so this is the right choice for most code.</li>
 *   <li>{@link #countAsync(TokenizationRequest)} — returns a {@link CompletableFuture}. Thin
 *       sugar over the sync path on a virtual thread.</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe — one {@code TokensClient} instance backs every call
 * on a given {@code FanarClient}.</p>
 *
 * @author Oussama Mahjoub
 */
public interface TokensClient {

    /**
     * Tokenize {@code request.content()} with {@code request.model()} and return the count.
     *
     * @param request the tokenization request; must not be {@code null}
     * @return the response carrying the token count and the per-request budget
     */
    TokenizationResponse count(TokenizationRequest request);

    /**
     * Same as {@link #count} but asynchronous. The returned future completes with the response
     * or exceptionally with a subtype of {@code FanarException}.
     */
    CompletableFuture<TokenizationResponse> countAsync(TokenizationRequest request);
}
