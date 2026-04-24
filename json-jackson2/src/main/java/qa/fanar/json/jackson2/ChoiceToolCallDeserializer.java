package qa.fanar.json.jackson2;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import qa.fanar.core.chat.ChoiceToolCall;
import qa.fanar.core.chat.ToolCallData;

/** Unwraps {@code choice.delta.tool_calls} (array) into {@link ChoiceToolCall#toolCalls()}. */
final class ChoiceToolCallDeserializer extends StdDeserializer<ChoiceToolCall> {

    ChoiceToolCallDeserializer() {
        super(ChoiceToolCall.class);
    }

    @Override
    public ChoiceToolCall deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
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
