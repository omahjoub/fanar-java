package qa.fanar.e2e;

import java.util.List;

import qa.fanar.core.chat.AssistantMessage;
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
        return pingFor(ChatModel.FANAR);
    }

    /** Same as {@link #ping()} but targets the given model. */
    public static ChatRequest pingFor(ChatModel model) {
        return ChatRequest.builder()
                .model(model)
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

    /** Multi-turn conversation: system, user, assistant, user — one turn for the model to respond to. */
    public static ChatRequest multiTurn() {
        return ChatRequest.builder()
                .model(ChatModel.FANAR)
                .addMessage(SystemMessage.of("Reply with one short word."))
                .addMessage(UserMessage.of("ping"))
                .addMessage(AssistantMessage.of("pong"))
                .addMessage(UserMessage.of("ping again"))
                .maxTokens(8)
                .temperature(0.0)
                .build();
    }

    /**
     * Thinking-enabled prompt against {@code Fanar-C-2-27B} — exercises {@code enable_thinking}.
     *
     * <p>Budget is generous (256) because the model emits its reasoning as inline
     * {@code <think>...</think>} markup inside {@code content}. A tighter budget exhausts
     * tokens during the reasoning phase and the actual answer never lands. Future SDK work:
     * expose thinking as a structured field on {@code ChatMessage} rather than leaving it
     * embedded in the content string.</p>
     */
    public static ChatRequest thinking() {
        return ChatRequest.builder()
                .model(ChatModel.FANAR_C_2_27B)
                .addMessage(SystemMessage.of("Reply with one short word."))
                .addMessage(UserMessage.of("What is 2 + 2?"))
                .enableThinking(true)
                .maxTokens(256)
                .temperature(0.0)
                .build();
    }

    /**
     * Fanar-Sadiq Islamic-RAG prompt with {@code restrict_to_islamic=true}. Should return
     * {@code references} populated and the streaming variant should emit {@code ProgressChunk}s.
     *
     * <p>{@code book_names} is intentionally left unset so Sadiq searches its full default
     * corpus — the {@code BookNamesEnum} catalogue (572 specific Arabic titles) is now reachable
     * through the typed {@code qa.fanar.core.chat.BookName} API for callers who want to scope
     * retrieval; this probe just doesn't exercise that knob.</p>
     */
    public static ChatRequest sadiq() {
        return ChatRequest.builder()
                .model(ChatModel.FANAR_SADIQ)
                .addMessage(UserMessage.of("Briefly summarise the meaning of Surah Al-Fatihah."))
                .restrictToIslamic(true)
                .maxTokens(96)
                .temperature(0.0)
                .build();
    }

    /** {@code n=3} multi-choice probe — verifies the response carries three independent choices. */
    public static ChatRequest tripleChoice() {
        return ChatRequest.builder()
                .model(ChatModel.FANAR)
                .addMessage(SystemMessage.of("Reply with one short word."))
                .addMessage(UserMessage.of("any number from 1 to 9"))
                .n(3)
                .maxTokens(8)
                .temperature(1.0)
                .build();
    }

    /**
     * Stop-sequence probe — sends {@code stop=["6"]} alongside a numeric continuation prompt.
     *
     * <p><strong>Server caveat (verified 2026-04-25):</strong> Fanar's {@code /v1/chat/completions}
     * endpoint <em>silently ignores the {@code stop} parameter</em>. Both {@code "\n"} and plain
     * words like {@code "6"} were sent on the wire and dropped server-side — the model continued
     * past them and stopped at {@code <end_of_turn>}. The companion live test therefore treats
     * §3.3 as a transport smoke check rather than a behavioural assertion. The offline
     * {@code ChatRequestKnobsTest.stopSequenceSerializesAsJsonArray} already proves the JSON
     * shape is correct.</p>
     *
     * <p>Future investigation: try {@code stop_token_ids} (numeric token IDs), which some
     * vLLM deployments honour separately from string {@code stop}.</p>
     */
    public static ChatRequest withStop() {
        return ChatRequest.builder()
                .model(ChatModel.FANAR)
                .addMessage(SystemMessage.of("Continue the sequence with numbers only, separated by commas."))
                .addMessage(UserMessage.of("1, 2, 3,"))
                .stop(List.of("6"))
                .maxTokens(16)
                .temperature(0.0)
                .build();
    }

    /** Logprobs probe — populates {@code ChoiceLogprobs} on the response. */
    public static ChatRequest withLogprobs() {
        return ChatRequest.builder()
                .model(ChatModel.FANAR)
                .addMessage(UserMessage.of("ping"))
                .logprobs(true)
                .topLogprobs(3)
                .maxTokens(4)
                .temperature(0.0)
                .build();
    }

}
