package qa.fanar.json.jackson2;

import java.io.IOException;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.FinishReason;
import qa.fanar.core.chat.ImageDetail;
import qa.fanar.core.chat.Source;
import qa.fanar.core.moderations.ModerationModel;

/**
 * Registers (de)serializers for the chat-package value-class records that expose their wire
 * format via {@code wireValue()} / {@code of(String)}.
 *
 * <p>Core types stay annotation-free (ADR-003 / ADR-015); this module is where Jackson learns
 * the mapping. {@code of(String)} is permissive — if Fanar emits a wire value the SDK doesn't
 * yet know about, decoding produces a value-class instance carrying the new wire string instead
 * of throwing.</p>
 */
final class WireValueModule {

    private WireValueModule() {
        // not instantiable
    }

    static SimpleModule create() {
        SimpleModule module = new SimpleModule("fanar-wire-value");
        register(module, ChatModel.class, ChatModel::wireValue, ChatModel::of);
        register(module, FinishReason.class, FinishReason::wireValue, FinishReason::of);
        register(module, ImageDetail.class, ImageDetail::wireValue, ImageDetail::of);
        register(module, Source.class, Source::wireValue, Source::of);
        register(module, ModerationModel.class, ModerationModel::wireValue, ModerationModel::of);
        return module;
    }

    private static <T> void register(
            SimpleModule module,
            Class<T> type,
            Function<T, String> toWire,
            Function<String, T> fromWire) {
        module.addSerializer(type, new WireSerializer<>(type, toWire));
        module.addDeserializer(type, new WireDeserializer<>(type, fromWire));
    }

    private static final class WireSerializer<T> extends StdSerializer<T> {
        private final Function<T, String> toWire;

        WireSerializer(Class<T> type, Function<T, String> toWire) {
            super(type);
            this.toWire = toWire;
        }

        @Override
        public void serialize(T value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(toWire.apply(value));
        }
    }

    private static final class WireDeserializer<T> extends StdDeserializer<T> {
        private final Function<String, T> fromWire;

        WireDeserializer(Class<T> type, Function<String, T> fromWire) {
            super(type);
            this.fromWire = fromWire;
        }

        @Override
        public T deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
            return fromWire.apply(parser.getValueAsString());
        }
    }
}
