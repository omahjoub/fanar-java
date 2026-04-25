package qa.fanar.json.jackson2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;

import qa.fanar.core.audio.SpeechToTextResponse;
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
import qa.fanar.core.translations.TranslationRequest;

/**
 * Jackson 2 binding for {@link FanarJsonCodec}.
 *
 * <p>Backed by an {@link ObjectMapper} configured for the Fanar wire format:</p>
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
 * <p>Thread-safe: an {@code ObjectMapper} is safe to share once configured. Callers who need
 * custom settings should build their own via {@link #defaultMapper()} and pass it to
 * {@link #Jackson2FanarJsonCodec(ObjectMapper)}.</p>
 */
public final class Jackson2FanarJsonCodec implements FanarJsonCodec {

    private final ObjectMapper mapper;

    /** Construct with the default Fanar-tuned mapper. */
    public Jackson2FanarJsonCodec() {
        this(defaultMapper());
    }

    /**
     * Construct with a caller-supplied mapper. The caller is responsible for registering the
     * {@link #fanarFlatteningModule()} if they want the flattened {@code Choice*} / progress
     * deserialization — {@link #defaultMapper()} already does so.
     */
    public Jackson2FanarJsonCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * A fresh {@link ObjectMapper} pre-configured with every default this adapter applies.
     * Callers who need to tweak one setting start from this mapper and call additional methods
     * on it before handing it to {@link #Jackson2FanarJsonCodec(ObjectMapper)}.
     */
    public static ObjectMapper defaultMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.addMixIn(Message.class, MessageMixIn.class);
        mapper.addMixIn(UserMessage.class, MessageContentMixIns.UserMessageMixIn.class);
        mapper.addMixIn(AssistantMessage.class, MessageContentMixIns.AssistantMessageMixIn.class);
        mapper.addMixIn(ChatMessage.class, MessageContentMixIns.ChatMessageMixIn.class);
        mapper.addMixIn(UserContentPart.class, ContentPartMixIns.UserContentPartMixIn.class);
        mapper.addMixIn(AssistantContentPart.class, ContentPartMixIns.AssistantContentPartMixIn.class);
        mapper.addMixIn(BookName.class, BookNameMixIn.class);
        mapper.addMixIn(TranslationRequest.class, TranslationRequestMixIn.class);
        mapper.registerModule(WireValueModule.create());
        mapper.registerModule(fanarFlatteningModule());
        return mapper;
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
        module.addDeserializer(SpeechToTextResponse.class, new SpeechToTextResponseDeserializer());
        return module;
    }

    @Override
    public <T> T decode(InputStream stream, Class<T> type) throws IOException {
        try {
            return mapper.readValue(stream, type);
        } catch (IOException e) {
            // Jackson 2's JacksonException extends IOException, so catching IOException covers
            // both Jackson-internal failures and stream-level I/O errors.
            throw new IOException("Failed to decode JSON as " + type.getSimpleName(), e);
        }
    }

    @Override
    public void encode(OutputStream stream, Object value) throws IOException {
        try {
            mapper.writeValue(stream, value);
        } catch (IOException e) {
            throw new IOException("Failed to encode JSON", e);
        }
    }
}
