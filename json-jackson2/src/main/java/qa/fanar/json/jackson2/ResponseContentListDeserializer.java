package qa.fanar.json.jackson2;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import qa.fanar.core.chat.ResponseContent;
import qa.fanar.core.chat.TextContent;

/**
 * Deserializer for {@code ChatMessage.content} that absorbs Fanar's shape polymorphism:
 * a bare JSON string is wrapped as a single-element {@link List} of {@link TextContent}.
 *
 * <p>Fanar's text-only backends return {@code "content": "pong"} for simple replies; the SDK
 * surfaces it via {@code List<ResponseContent>} on {@code ChatMessage}, so the adapter has to
 * lift the string into a list. {@code null} is handled upstream by Jackson and normalised to
 * an empty list by {@code ChatMessage}'s compact constructor — no branch needed here.</p>
 *
 * <p>Multi-part responses ({@code "content": [{type:..., ...}, ...]}) will need a type-info
 * mix-in on {@link ResponseContent} plus an array branch here. Deferred until a test actually
 * exercises one.</p>
 *
 * @author Oussama Mahjoub
 */
final class ResponseContentListDeserializer extends StdDeserializer<List<ResponseContent>> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    ResponseContentListDeserializer() {
        super((Class<List<ResponseContent>>) (Class) List.class);
    }

    @Override
    public List<ResponseContent> deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_STRING) {
            return List.of(new TextContent(parser.getText()));
        }
        // Empty array round-trips trivially; non-empty arrays need a type-info mix-in we
        // haven't shipped yet, so they're rejected explicitly below.
        if (token == JsonToken.START_ARRAY && parser.nextToken() == JsonToken.END_ARRAY) {
            return List.of();
        }
        throw MismatchedInputException.from(parser, handledType(),
                "Expected string or empty array for ChatMessage.content, got " + token);
    }
}
