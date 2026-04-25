package qa.fanar.core.models;

import java.util.Objects;

/**
 * One entry in the {@code GET /v1/models} response — describes a model the API key has access to.
 *
 * <p>{@code id} is the same string Fanar accepts in the {@code model} field of a chat-completion
 * request; pass it through {@link qa.fanar.core.chat.ChatModel#of(String)} when a typed value is
 * needed. The endpoint can list non-chat models too (audio voices, image models in the future),
 * which is why this type stays a plain string rather than a {@code ChatModel}.</p>
 *
 * @param id        wire model identifier, e.g. {@code "Fanar"}
 * @param object    discriminator constant — always {@code "model"} on this endpoint, kept for
 *                  wire fidelity
 * @param created   Unix epoch seconds at which the model was registered
 * @param ownedBy   organization that owns the model, e.g. {@code "QCRI"} (Qatar Computing
 *                  Research Institute) for first-party Fanar models
 */
public record AvailableModel(
        String id,
        String object,
        long created,
        String ownedBy
) {

    public AvailableModel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(ownedBy, "ownedBy");
    }
}
