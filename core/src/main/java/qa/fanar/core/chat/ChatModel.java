package qa.fanar.core.chat;

import java.util.Objects;

/**
 * Typed identifiers for the models that accept chat-completion requests.
 *
 * <p>Mirrors the {@code ChatCompletionLLM} enum in the Fanar OpenAPI spec. Wire values are the
 * exact strings Fanar accepts in the {@code model} field on {@code POST /v1/chat/completions}.
 * Roundtrip via {@link #wireValue()} and {@link #fromWireValue(String)}.</p>
 */
public enum ChatModel {

    /** Router: picks the right backend model for the query. Default rate limit 50/min. */
    FANAR("Fanar"),

    /** "Star" chat model, 7B parameters. Rate limit 50/min. */
    FANAR_S_1_7B("Fanar-S-1-7B"),

    /** "Commander" chat model with thinking support, version 1 (8.7B). Rate limit 50/min. */
    FANAR_C_1_8_7B("Fanar-C-1-8.7B"),

    /**
     * "Commander" chat model with thinking support, version 2 (27B). Required for the
     * {@code enable_thinking} request flag; extra authorization required.
     */
    FANAR_C_2_27B("Fanar-C-2-27B"),

    /** Islamic RAG model. Returns authenticated source references. Rate limit 50/min. */
    FANAR_SADIQ("Fanar-Sadiq"),

    /** Vision-language model. Arabic-calligraphy-aware. Rate limit 20/day. */
    FANAR_ORYX_IVU_2("Fanar-Oryx-IVU-2");

    private final String wireValue;

    ChatModel(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The exact string Fanar accepts on the wire for this model. */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Look up an enum value by its wire format.
     *
     * @param value the wire-format string; must not be {@code null}
     * @return the matching enum value
     * @throws IllegalArgumentException if no enum value matches
     * @throws NullPointerException     if {@code value} is {@code null}
     */
    public static ChatModel fromWireValue(String value) {
        Objects.requireNonNull(value, "value");
        for (ChatModel model : values()) {
            if (model.wireValue.equals(value)) {
                return model;
            }
        }
        throw new IllegalArgumentException("Unknown ChatModel wire value: " + value);
    }
}
