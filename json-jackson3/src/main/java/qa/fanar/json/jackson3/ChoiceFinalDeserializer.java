package qa.fanar.json.jackson3;

import java.util.List;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.deser.std.StdDeserializer;

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
    public ChoiceFinal deserialize(JsonParser parser, DeserializationContext ctxt) {
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
