package qa.fanar.json.jackson3;

import java.util.List;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

import qa.fanar.core.chat.TextPart;

/**
 * Serializer for a message's {@code content} list that collapses a single {@link TextPart} to a
 * plain JSON string, and otherwise emits a typed content-part array.
 *
 * <p>OpenAI-compatible convention: {@code "content": "hi"} for simple text and
 * {@code "content": [{type:..., ...}, ...]} for multi-modal. Fanar's server uses the shape of
 * the content field to route to text-only vs. multi-modal backends, so text-only models reject
 * single-part arrays even when they carry only text.</p>
 *
 * <p>Attached to {@code UserMessage.content()} and {@code AssistantMessage.content()} via
 * mix-ins.</p>
 */
final class ContentListSerializer extends StdSerializer<List<?>> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    ContentListSerializer() {
        super((Class<List<?>>) (Class) List.class);
    }

    @Override
    public void serialize(List<?> value, JsonGenerator gen, SerializationContext ctxt) {
        if (value.size() == 1 && value.getFirst() instanceof TextPart(String text)) {
            gen.writeString(text);
            return;
        }
        gen.writeStartArray();
        for (Object element : value) {
            ctxt.writeValue(gen, element);
        }
        gen.writeEndArray();
    }
}
