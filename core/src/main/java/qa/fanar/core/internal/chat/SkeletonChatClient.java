package qa.fanar.core.internal.chat;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import qa.fanar.core.chat.ChatClient;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.ChatResponse;
import qa.fanar.core.chat.StreamEvent;

/**
 * Placeholder implementation of {@link ChatClient} that throws
 * {@link UnsupportedOperationException} on every call.
 *
 * <p>Part of ADR-016's first-pass scaffolding: callers can construct a {@code FanarClient} and
 * traverse the domain facade surface, but no transport exists yet. Replaced by the real
 * implementation in the transport PR.</p>
 *
 * <p>Internal implementation detail (ADR-018). Not part of the public API; may be replaced,
 * renamed, or deleted in any release.</p>
 */
public final class SkeletonChatClient implements ChatClient {

    private static final String NOT_YET =
            "This method is not yet implemented. The transport layer lands in a follow-up PR; "
                    + "until then, FanarClient only exposes the contract surface.";

    @Override
    public ChatResponse send(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        throw new UnsupportedOperationException(NOT_YET);
    }

    @Override
    public CompletableFuture<ChatResponse> sendAsync(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        throw new UnsupportedOperationException(NOT_YET);
    }

    @Override
    public Flow.Publisher<StreamEvent> stream(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        throw new UnsupportedOperationException(NOT_YET);
    }
}
