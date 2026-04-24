package qa.fanar.json.jackson2;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import qa.fanar.core.chat.ChoiceFinal;
import qa.fanar.core.chat.Reference;

/**
 * Unwraps {@code choice.delta.references} (array) into {@link ChoiceFinal#references()}.
 * The record itself defaults {@code finishReason} to {@code "stop"} and {@code references} to
 * an empty list when the arguments are {@code null}, so this deserializer does not need to
 * synthesise defaults.
 */
final class ChoiceFinalDeserializer extends StdDeserializer<ChoiceFinal> {

    ChoiceFinalDeserializer() {
        super(ChoiceFinal.class);
    }

    @Override
    public ChoiceFinal deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
        JsonNode choice = ctxt.readTree(parser);
        JsonNode delta = ChoiceNodes.delta(choice);
        List<Reference> references = delta == null
                ? List.of()
                : ChoiceNodes.readList(delta.path("references"), Reference.class, ctxt);
        return new ChoiceFinal(
                ChoiceNodes.index(choice),
                ChoiceNodes.finishReason(choice),
                references);
    }
}
