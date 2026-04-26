package qa.fanar.core.chat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Domain facade for chat-completion operations. Returned by {@code FanarClient.chat()}.
 *
 * <p>Three variants of the same call — picked by shape, not by configuration:</p>
 * <ul>
 *   <li>{@link #send(ChatRequest)} — blocking. On Java 21 virtual threads the block doesn't
 *       pin a carrier, so this is the right choice for most code.</li>
 *   <li>{@link #sendAsync(ChatRequest)} — returns a {@link CompletableFuture}. Thin sugar over
 *       the sync path on a virtual-thread executor; adapts trivially to Reactor / RxJava /
 *       Kotlin coroutines.</li>
 *   <li>{@link #stream(ChatRequest)} — returns a {@link Flow.Publisher} of {@link StreamEvent}s
 *       for token-by-token consumption. Canonical streaming surface; see the sealed
 *       {@link StreamEvent} hierarchy for the six chunk shapes Fanar emits.</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe — one {@code ChatClient} instance backs every call on
 * a given {@code FanarClient}.</p>
 *
 * @author Oussama Mahjoub
 */
public interface ChatClient {

    /**
     * Send a chat-completion request and block until the full response is received.
     *
     * @param request the request to send; must not be {@code null}
     * @return the completion response
     */
    ChatResponse send(ChatRequest request);

    /**
     * Send a chat-completion request asynchronously. The returned future completes with the
     * response or exceptionally with a subtype of {@code FanarException}.
     *
     * @param request the request to send; must not be {@code null}
     * @return a future carrying the completion response
     */
    CompletableFuture<ChatResponse> sendAsync(ChatRequest request);

    /**
     * Send a streaming chat-completion request. The returned publisher emits
     * {@link StreamEvent}s as the server sends them, terminates with a {@link DoneChunk}, and
     * signals errors via {@link Flow.Subscriber#onError(Throwable)}.
     *
     * @param request the request to send; must not be {@code null}
     * @return a publisher over streaming events
     */
    Flow.Publisher<StreamEvent> stream(ChatRequest request);
}
