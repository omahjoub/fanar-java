package qa.fanar.core.chat;

import java.util.Objects;
import java.util.Set;

/**
 * Reason the model stopped generating for a given {@link ChatChoice}.
 *
 * <p>Mirrors the {@code finish_reason} schema in the Fanar OpenAPI spec — but open: if Fanar
 * ever adds a new reason ({@code "safety"}, {@code "partial"}, …) the SDK will decode it
 * gracefully into a {@code FinishReason} carrying the new wire string instead of failing.
 * {@link #KNOWN} is the snapshot bundled with this build.</p>
 *
 * @param wireValue the exact string Fanar emits on the wire
 *
 * @author Oussama Mahjoub
 */
public record FinishReason(String wireValue) {

    /** Natural end — the model finished its turn. */
    public static final FinishReason STOP            = new FinishReason("stop");

    /** Generation was cut short by {@code maxTokens}. */
    public static final FinishReason LENGTH          = new FinishReason("length");

    /** Model invoked one or more tools; see {@link ChatMessage#toolCalls()}. */
    public static final FinishReason TOOL_CALLS      = new FinishReason("tool_calls");

    /** Fanar's content-filter layer blocked the response. */
    public static final FinishReason CONTENT_FILTER  = new FinishReason("content_filter");

    /**
     * Legacy finish reason from the OpenAI function-calling era. Retained for wire compatibility
     * but Fanar does not currently emit this value.
     */
    public static final FinishReason FUNCTION_CALL   = new FinishReason("function_call");

    /** Snapshot of the SDK's bundled constants. */
    public static final Set<FinishReason> KNOWN = Set.of(
            STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER, FUNCTION_CALL);

    public FinishReason {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new FinishReason(wireValue)}; provided for API symmetry with other types. */
    public static FinishReason of(String wireValue) {
        return new FinishReason(wireValue);
    }
}
