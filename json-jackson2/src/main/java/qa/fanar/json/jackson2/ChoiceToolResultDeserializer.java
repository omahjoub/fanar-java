package qa.fanar.json.jackson2;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import qa.fanar.core.chat.ChoiceToolResult;
import qa.fanar.core.chat.ToolResultData;

/**
 * Unwraps {@code choice.delta.tool_result} (object) into {@link ChoiceToolResult#toolResult()}.
 * The record requires a non-null {@code toolResult}; absence is a shape mismatch — the SDK's
 * shape classifier routes here only when {@code delta.tool_result} is present, so if this
 * deserializer fires without it, the record's canonical-constructor NPE is the right signal.
 */
final class ChoiceToolResultDeserializer extends StdDeserializer<ChoiceToolResult> {

    ChoiceToolResultDeserializer() {
        super(ChoiceToolResult.class);
    }

    @Override
    public ChoiceToolResult deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
        JsonNode choice = ctxt.readTree(parser);
        JsonNode delta = ChoiceNodes.delta(choice);
        ToolResultData result = null;
        if (delta != null) {
            JsonNode resultNode = delta.path("tool_result");
            if (!resultNode.isMissingNode() && !resultNode.isNull()) {
                result = ctxt.readTreeAsValue(resultNode, ToolResultData.class);
            }
        }
        return new ChoiceToolResult(
                ChoiceNodes.index(choice),
                ChoiceNodes.finishReason(choice),
                result);
    }
}
