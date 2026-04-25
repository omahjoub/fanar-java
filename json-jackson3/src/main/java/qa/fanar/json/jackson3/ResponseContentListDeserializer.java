package qa.fanar.json.jackson3;

import java.util.List;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;

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
 */
final class ResponseContentListDeserializer extends StdDeserializer<List<ResponseContent>> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    ResponseContentListDeserializer() {
        super((Class<List<ResponseContent>>) (Class) List.class);
    }

    @Override
    public List<ResponseContent> deserialize(JsonParser parser, DeserializationContext ctxt) {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_STRING) {
            return List.of(new TextContent(parser.getString()));
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
