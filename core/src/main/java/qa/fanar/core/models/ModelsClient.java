package qa.fanar.core.models;

import java.util.concurrent.CompletableFuture;

/**
 * Domain facade for the {@code /v1/models} endpoint. Returned by {@code FanarClient.models()}.
 *
 * <p>Two variants — same call, different waiting strategies:</p>
 * <ul>
 *   <li>{@link #list()} — blocking. On Java 21 virtual threads the block doesn't pin a carrier,
 *       so this is the right choice for most code.</li>
 *   <li>{@link #listAsync()} — returns a {@link CompletableFuture}. Thin sugar over the sync path
 *       on a virtual thread; adapts trivially to Reactor / RxJava / Kotlin coroutines.</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe — one {@code ModelsClient} instance backs every call on
 * a given {@code FanarClient}.</p>
 *
 * @author Oussama Mahjoub
 */
public interface ModelsClient {

    /**
     * Fetch the list of models the configured API key has access to.
     *
     * @return the response carrying the available models
     */
    ModelsResponse list();

    /**
     * Same as {@link #list()} but asynchronous. The returned future completes with the response
     * or exceptionally with a subtype of {@code FanarException}.
     *
     * @return a future carrying the response
     */
    CompletableFuture<ModelsResponse> listAsync();
}
