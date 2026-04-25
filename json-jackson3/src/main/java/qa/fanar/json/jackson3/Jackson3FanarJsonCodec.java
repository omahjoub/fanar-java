package qa.fanar.json.jackson3;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import qa.fanar.core.chat.AssistantContentPart;
import qa.fanar.core.chat.AssistantMessage;
import qa.fanar.core.chat.BookName;
import qa.fanar.core.chat.ChatMessage;
import qa.fanar.core.chat.ChoiceError;
import qa.fanar.core.chat.ChoiceFinal;
import qa.fanar.core.chat.ChoiceToken;
import qa.fanar.core.chat.ChoiceToolCall;
import qa.fanar.core.chat.ChoiceToolResult;
import qa.fanar.core.chat.Message;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.UserContentPart;
import qa.fanar.core.chat.UserMessage;
import qa.fanar.core.spi.FanarJsonCodec;

/**
 * Jackson 3 binding for {@link FanarJsonCodec}.
 *
 * <p>Backed by a {@link JsonMapper} configured for the Fanar wire format:</p>
 * <ul>
 *   <li>Snake-case property naming — matches the Fanar OpenAPI verbatim so records can stay
 *       annotation-free.</li>
 *   <li>{@code NON_NULL} inclusion on serialization — optional request fields don't leak
 *       {@code "field":null} over the wire.</li>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES} disabled — forward-compatible with server additions.</li>
 *   <li>Six custom deserializers flatten {@code delta.*} and {@code progress.message} one level
 *       so the SDK records stay shallow.</li>
 * </ul>
 *
 * <p>Thread-safe: a {@code JsonMapper} is safe to share once configured. Callers who need custom
 * settings should build their own via {@link #defaultMapperBuilder()} and pass it to
 * {@link #Jackson3FanarJsonCodec(JsonMapper)}.</p>
 */
public final class Jackson3FanarJsonCodec implements FanarJsonCodec {

    private final JsonMapper mapper;

    /** Construct with the default Fanar-tuned mapper. */
    public Jackson3FanarJsonCodec() {
        this(defaultMapperBuilder().build());
    }

    /**
     * Construct with a caller-supplied mapper. The caller is responsible for registering the
     * {@link #fanarFlatteningModule()} if they want the flattened {@code Choice*} / progress
     * deserialization — {@link #defaultMapperBuilder()} already does so.
     */
    public Jackson3FanarJsonCodec(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * A builder pre-populated with every default this adapter applies. Callers who need to
     * tweak one setting (for example, enabling pretty-printing for debugging) start from this
     * builder and call additional methods before {@code build()}.
     */
    public static JsonMapper.Builder defaultMapperBuilder() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .addMixIn(Message.class, MessageMixIn.class)
                .addMixIn(UserMessage.class, MessageContentMixIns.UserMessageMixIn.class)
                .addMixIn(AssistantMessage.class, MessageContentMixIns.AssistantMessageMixIn.class)
                .addMixIn(ChatMessage.class, MessageContentMixIns.ChatMessageMixIn.class)
                .addMixIn(UserContentPart.class, ContentPartMixIns.UserContentPartMixIn.class)
                .addMixIn(AssistantContentPart.class, ContentPartMixIns.AssistantContentPartMixIn.class)
                .addMixIn(BookName.class, BookNameMixIn.class)
                .addModule(WireValueEnumModule.create())
                .addModule(fanarFlatteningModule());
    }

    /**
     * The Jackson module that registers the six flattening deserializers for {@link ChoiceToken},
     * {@link ChoiceToolCall}, {@link ChoiceToolResult}, {@link ChoiceFinal}, {@link ChoiceError},
     * and {@link ProgressChunk}. Public so callers who build their own mapper from scratch can
     * still pick up the Fanar-specific unwrapping.
     */
    public static SimpleModule fanarFlatteningModule() {
        SimpleModule module = new SimpleModule("fanar-flattening");
        module.addDeserializer(ChoiceToken.class, new ChoiceTokenDeserializer());
        module.addDeserializer(ChoiceToolCall.class, new ChoiceToolCallDeserializer());
        module.addDeserializer(ChoiceToolResult.class, new ChoiceToolResultDeserializer());
        module.addDeserializer(ChoiceFinal.class, new ChoiceFinalDeserializer());
        module.addDeserializer(ChoiceError.class, new ChoiceErrorDeserializer());
        module.addDeserializer(ProgressChunk.class, new ProgressChunkDeserializer());
        return module;
    }

    @Override
    public <T> T decode(InputStream stream, Class<T> type) throws IOException {
        try {
            return mapper.readValue(stream, type);
        } catch (JacksonException e) {
            throw new IOException("Failed to decode JSON as " + type.getSimpleName(), e);
        }
    }

    @Override
    public void encode(OutputStream stream, Object value) throws IOException {
        try {
            mapper.writeValue(stream, value);
        } catch (JacksonException e) {
            throw new IOException("Failed to encode JSON", e);
        }
    }
}
