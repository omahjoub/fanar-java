package qa.fanar.json.jackson3;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.deser.std.StdDeserializer;

import qa.fanar.core.chat.ChoiceToken;

/**
 * Unwraps {@code choice.delta.content} (String) into {@link ChoiceToken#content()}.
 * Missing or {@code null} content becomes the empty string so the record's non-null invariant
 * holds for the common "finish-reason only" tail chunk that carries no token delta.
 *
 * @author Oussama Mahjoub
 */
final class ChoiceTokenDeserializer extends StdDeserializer<ChoiceToken> {

    ChoiceTokenDeserializer() {
        super(ChoiceToken.class);
    }

    @Override
    public ChoiceToken deserialize(JsonParser parser, DeserializationContext ctxt) {
        JsonNode choice = ctxt.readTree(parser);
        JsonNode delta = ChoiceNodes.delta(choice);
        String content = delta == null ? null : ChoiceNodes.textOrNull(delta, "content");
        return new ChoiceToken(
                ChoiceNodes.index(choice),
                ChoiceNodes.finishReason(choice),
                content == null ? "" : content);
    }
}
