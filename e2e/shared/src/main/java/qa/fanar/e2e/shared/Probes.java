package qa.fanar.e2e.shared;

import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.ChatRequest;
import qa.fanar.core.chat.SystemMessage;
import qa.fanar.core.chat.UserMessage;

/**
 * Canned {@link ChatRequest} probes used by e2e tests. Kept deliberately tiny so they cost
 * the smallest possible amount of Fanar rate-limit budget per run.
 */
public final class Probes {

    private Probes() {
        // not instantiable
    }

    /** A minimal "ping" prompt — one user message, the default model, short output. */
    public static ChatRequest ping() {
        return ChatRequest.builder()
                .model(ChatModel.FANAR)
                .addMessage(SystemMessage.of(
                        "You are a test probe. Reply with a single short English word."))
                .addMessage(UserMessage.of("ping"))
                .maxTokens(8)
                .temperature(0.0)
                .build();
    }

    /** A streaming-friendly prompt that should emit multiple token chunks. */
    public static ChatRequest streamingPing() {
        return ChatRequest.builder()
                .model(ChatModel.FANAR)
                .addMessage(SystemMessage.of(
                        "You are a test probe. Count from 1 to 5 in English, one number per line."))
                .addMessage(UserMessage.of("count"))
                .maxTokens(32)
                .temperature(0.0)
                .build();
    }

    /**
     * A prompt with a bogus API key — used to assert that 401 propagates as a typed
     * {@code FanarAuthenticationException}.
     */
    public static ChatRequest bogusAuthProbe() {
        return ChatRequest.builder()
                .model(ChatModel.FANAR)
                .addMessage(UserMessage.of("hello"))
                .maxTokens(1)
                .build();
    }
}
