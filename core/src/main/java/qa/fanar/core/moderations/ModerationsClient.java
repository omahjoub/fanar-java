package qa.fanar.core.moderations;

import java.util.concurrent.CompletableFuture;

/**
 * Domain facade for the {@code /v1/moderations} endpoint. Returned by
 * {@code FanarClient.moderations()}.
 *
 * <p>Two variants — same call, different waiting strategies:</p>
 * <ul>
 *   <li>{@link #score(SafetyFilterRequest)} — blocking. On Java 21 virtual threads the block
 *       doesn't pin a carrier, so this is the right choice for most code.</li>
 *   <li>{@link #scoreAsync(SafetyFilterRequest)} — returns a {@link CompletableFuture}. Thin
 *       sugar over the sync path on a virtual thread.</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe — one {@code ModerationsClient} instance backs every
 * call on a given {@code FanarClient}.</p>
 */
public interface ModerationsClient {

    /**
     * Score a prompt/response pair for safety and cultural awareness.
     *
     * @param request the prompt/response pair plus moderation model to use; must not be {@code null}
     * @return safety + cultural-awareness scores
     */
    SafetyFilterResponse score(SafetyFilterRequest request);

    /**
     * Same as {@link #score} but asynchronous. The returned future completes with the response
     * or exceptionally with a subtype of {@code FanarException}.
     */
    CompletableFuture<SafetyFilterResponse> scoreAsync(SafetyFilterRequest request);
}
