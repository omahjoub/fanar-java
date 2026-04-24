package qa.fanar.json.jackson3;

import java.util.List;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.deser.std.StdDeserializer;

import qa.fanar.core.chat.ChoiceToolCall;
import qa.fanar.core.chat.ToolCallData;

/** Unwraps {@code choice.delta.tool_calls} (array) into {@link ChoiceToolCall#toolCalls()}. */
final class ChoiceToolCallDeserializer extends StdDeserializer<ChoiceToolCall> {

    ChoiceToolCallDeserializer() {
        super(ChoiceToolCall.class);
    }

    @Override
    public ChoiceToolCall deserialize(JsonParser parser, DeserializationContext ctxt) {
        JsonNode choice = ctxt.readTree(parser);
        JsonNode delta = ChoiceNodes.delta(choice);
        List<ToolCallData> calls = delta == null
                ? List.of()
                : ChoiceNodes.readList(delta.path("tool_calls"), ToolCallData.class, ctxt);
        return new ChoiceToolCall(
                ChoiceNodes.index(choice),
                ChoiceNodes.finishReason(choice),
                calls);
    }
}
