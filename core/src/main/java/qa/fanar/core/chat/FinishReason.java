package qa.fanar.core.chat;

import java.util.Objects;

/**
 * Reason the model stopped generating for a given {@link ChatChoice}.
 *
 * <p>Mirrors the {@code finish_reason} enum in the Fanar OpenAPI spec. Wire values are the
 * lowercase strings Fanar sends on the wire; roundtrip via {@link #wireValue()} and
 * {@link #fromWireValue(String)}.</p>
 */
public enum FinishReason {

    /** Natural end — the model finished its turn. */
    STOP("stop"),

    /** Generation was cut short by {@code maxTokens}. */
    LENGTH("length"),

    /** Model invoked one or more tools; see {@link ChatMessage#toolCalls()}. */
    TOOL_CALLS("tool_calls"),

    /** Fanar's content-filter layer blocked the response. */
    CONTENT_FILTER("content_filter"),

    /**
     * Legacy finish reason from the OpenAI function-calling era. Retained for wire compatibility
     * but Fanar does not currently emit this value.
     */
    FUNCTION_CALL("function_call");

    private final String wireValue;

    FinishReason(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The exact string Fanar uses on the wire. */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Look up an enum value by its wire format.
     *
     * @throws IllegalArgumentException if no enum value matches
     * @throws NullPointerException     if {@code value} is {@code null}
     */
    public static FinishReason fromWireValue(String value) {
        Objects.requireNonNull(value, "value");
        for (FinishReason fr : values()) {
            if (fr.wireValue.equals(value)) {
                return fr;
            }
        }
        throw new IllegalArgumentException("Unknown FinishReason wire value: " + value);
    }
}
