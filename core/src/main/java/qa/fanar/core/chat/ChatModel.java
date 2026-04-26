package qa.fanar.core.chat;

import java.util.Objects;
import java.util.Set;

/**
 * Typed identifier for a Fanar chat-completion model.
 *
 * <p>Mirrors the {@code ChatCompletionLLM} schema in the Fanar OpenAPI spec — but open: when
 * Fanar ships a new model, callers can target it the same day via {@link #of(String)} without
 * waiting for an SDK release. {@link #KNOWN} is the snapshot of models bundled with this build,
 * useful for IDE autocomplete and as an advisory catalogue, not a gate.</p>
 *
 * @param wireValue the exact string Fanar accepts in the {@code model} field
 *
 * @author Oussama Mahjoub
 */
public record ChatModel(String wireValue) {

    /** Router: picks the right backend model for the query. Default rate limit 50/min. */
    public static final ChatModel FANAR            = new ChatModel("Fanar");

    /** "Star" chat model, 7B parameters. Rate limit 50/min. */
    public static final ChatModel FANAR_S_1_7B     = new ChatModel("Fanar-S-1-7B");

    /** "Commander" chat model with thinking support, version 1 (8.7B). Rate limit 50/min. */
    public static final ChatModel FANAR_C_1_8_7B   = new ChatModel("Fanar-C-1-8.7B");

    /**
     * "Commander" chat model with thinking support, version 2 (27B). Required for the
     * {@code enable_thinking} request flag; extra authorization required.
     */
    public static final ChatModel FANAR_C_2_27B    = new ChatModel("Fanar-C-2-27B");

    /** Islamic RAG model. Returns authenticated source references. Rate limit 50/min. */
    public static final ChatModel FANAR_SADIQ      = new ChatModel("Fanar-Sadiq");

    /** Vision-language model. Arabic-calligraphy-aware. Rate limit 20/day. */
    public static final ChatModel FANAR_ORYX_IVU_2 = new ChatModel("Fanar-Oryx-IVU-2");

    /** Snapshot of the SDK's bundled constants. Use for iteration, autocomplete catalogues, and tests. */
    public static final Set<ChatModel> KNOWN = Set.of(
            FANAR, FANAR_S_1_7B, FANAR_C_1_8_7B, FANAR_C_2_27B, FANAR_SADIQ, FANAR_ORYX_IVU_2);

    public ChatModel {
        Objects.requireNonNull(wireValue, "wireValue");
    }

    /** Equivalent to {@code new ChatModel(wireValue)}; provided for API symmetry with other types. */
    public static ChatModel of(String wireValue) {
        return new ChatModel(wireValue);
    }
}
