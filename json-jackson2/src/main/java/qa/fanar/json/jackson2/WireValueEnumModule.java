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

/**
 * Registers (de)serializers for the four chat-package enums that expose their wire format via
 * {@code wireValue()} / {@code fromWireValue(...)} instead of {@link Enum#name()}.
 *
 * <p>The core enums stay annotation-free (ADR-003 / ADR-015 — no framework coupling); this
 * module is where Jackson learns the mapping.</p>
 */
final class WireValueEnumModule {

    private WireValueEnumModule() {
        // not instantiable
    }

    static SimpleModule create() {
        SimpleModule module = new SimpleModule("fanar-wire-value-enums");
        register(module, ChatModel.class, ChatModel::wireValue, ChatModel::fromWireValue);
        register(module, FinishReason.class, FinishReason::wireValue, FinishReason::fromWireValue);
        register(module, ImageDetail.class, ImageDetail::wireValue, ImageDetail::fromWireValue);
        register(module, Source.class, Source::wireValue, Source::fromWireValue);
        return module;
    }

    private static <E extends Enum<E>> void register(
            SimpleModule module,
            Class<E> type,
            Function<E, String> toWire,
            Function<String, E> fromWire) {
        module.addSerializer(type, new WireSerializer<>(type, toWire));
        module.addDeserializer(type, new WireDeserializer<>(type, fromWire));
    }

    private static final class WireSerializer<E extends Enum<E>> extends StdSerializer<E> {
        private final Function<E, String> toWire;

        WireSerializer(Class<E> type, Function<E, String> toWire) {
            super(type);
            this.toWire = toWire;
        }

        @Override
        public void serialize(E value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(toWire.apply(value));
        }
    }

    private static final class WireDeserializer<E> extends StdDeserializer<E> {
        private final Function<String, E> fromWire;

        WireDeserializer(Class<E> type, Function<String, E> fromWire) {
            super(type);
            this.fromWire = fromWire;
        }

        @Override
        public E deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
            return fromWire.apply(parser.getValueAsString());
        }
    }
}
