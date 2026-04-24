package qa.fanar.json.jackson2;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import qa.fanar.core.chat.ChoiceError;

/**
 * Unwraps {@code choice.delta.content} (String) into {@link ChoiceError#content()}. The record's
 * canonical constructor defaults a missing {@code finishReason} to {@code "error"}, so we only
 * need to supply the content.
 */
final class ChoiceErrorDeserializer extends StdDeserializer<ChoiceError> {

    ChoiceErrorDeserializer() {
        super(ChoiceError.class);
    }

    @Override
    public ChoiceError deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
        JsonNode choice = ctxt.readTree(parser);
        JsonNode delta = ChoiceNodes.delta(choice);
        String content = delta == null ? null : ChoiceNodes.textOrNull(delta, "content");
        return new ChoiceError(
                ChoiceNodes.index(choice),
                ChoiceNodes.finishReason(choice),
                content == null ? "" : content);
    }
}
