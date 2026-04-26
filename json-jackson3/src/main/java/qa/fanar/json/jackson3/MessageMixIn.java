package qa.fanar.json.jackson3;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import qa.fanar.core.chat.AssistantMessage;
import qa.fanar.core.chat.SystemMessage;
import qa.fanar.core.chat.ThinkingMessage;
import qa.fanar.core.chat.ThinkingUserMessage;
import qa.fanar.core.chat.UserMessage;

/**
 * Jackson mix-in that teaches the serializer to emit a {@code "role"} discriminator property on
 * each {@link qa.fanar.core.chat.Message} subtype. Fanar's server requires this field on every
 * message; the core sealed hierarchy carries the role implicitly in the Java type, so the
 * adapter is the right layer to inject it.
 *
 * <p>Wired via {@code mapper.addMixIn(Message.class, MessageMixIn.class)} — the mixin annotations
 * are applied to {@code Message} at runtime without touching the core type.</p>
 *
 * @author Oussama Mahjoub
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "role")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SystemMessage.class,        name = "system"),
        @JsonSubTypes.Type(value = UserMessage.class,          name = "user"),
        @JsonSubTypes.Type(value = AssistantMessage.class,     name = "assistant"),
        @JsonSubTypes.Type(value = ThinkingMessage.class,      name = "thinking"),
        @JsonSubTypes.Type(value = ThinkingUserMessage.class,  name = "thinking_user"),
})
interface MessageMixIn {
    // Annotations only — Jackson applies them to the target type via addMixIn.
}
