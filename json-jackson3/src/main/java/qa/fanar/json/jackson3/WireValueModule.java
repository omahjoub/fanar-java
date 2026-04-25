package qa.fanar.json.jackson3;

import java.util.function.Function;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

import qa.fanar.core.audio.QuranReciter;
import qa.fanar.core.audio.SttFormat;
import qa.fanar.core.audio.SttModel;
import qa.fanar.core.audio.TtsModel;
import qa.fanar.core.audio.TtsResponseFormat;
import qa.fanar.core.audio.Voice;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.FinishReason;
import qa.fanar.core.chat.ImageDetail;
import qa.fanar.core.chat.Source;
import qa.fanar.core.images.ImageModel;
import qa.fanar.core.moderations.ModerationModel;
import qa.fanar.core.poems.PoemModel;
import qa.fanar.core.translations.LanguagePair;
import qa.fanar.core.translations.TranslationModel;
import qa.fanar.core.translations.TranslationPreprocessing;

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
        register(module, TranslationModel.class, TranslationModel::wireValue, TranslationModel::of);
        register(module, LanguagePair.class, LanguagePair::wireValue, LanguagePair::of);
        register(module, TranslationPreprocessing.class,
                TranslationPreprocessing::wireValue, TranslationPreprocessing::of);
        register(module, PoemModel.class, PoemModel::wireValue, PoemModel::of);
        register(module, ImageModel.class, ImageModel::wireValue, ImageModel::of);
        register(module, TtsModel.class, TtsModel::wireValue, TtsModel::of);
        register(module, TtsResponseFormat.class, TtsResponseFormat::wireValue, TtsResponseFormat::of);
        register(module, QuranReciter.class, QuranReciter::wireValue, QuranReciter::of);
        register(module, Voice.class, Voice::wireValue, Voice::of);
        register(module, SttModel.class, SttModel::wireValue, SttModel::of);
        register(module, SttFormat.class, SttFormat::wireValue, SttFormat::of);
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
        public void serialize(T value, JsonGenerator gen, SerializationContext ctxt) {
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
        public T deserialize(JsonParser parser, DeserializationContext ctxt) {
            return fromWire.apply(parser.getValueAsString());
        }
    }
}
