package qa.fanar.json.jackson3;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.deser.std.StdDeserializer;

import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.ProgressMessage;

/**
 * Unwraps {@code progress.message.{en,ar}} into {@link ProgressChunk#message()}. The wire shape
 * nests the bilingual strings two levels deep inside a {@code progress} object; this
 * deserializer hoists them onto the chunk directly.
 */
final class ProgressChunkDeserializer extends StdDeserializer<ProgressChunk> {

    ProgressChunkDeserializer() {
        super(ProgressChunk.class);
    }

    @Override
    public ProgressChunk deserialize(JsonParser parser, DeserializationContext ctxt) {
        JsonNode node = ctxt.readTree(parser);
        // `path()` chains through MissingNode / NullNode, so absent or JSON-null {@code progress}
        // collapses to a MissingNode here without explicit null-checks.
        JsonNode messageNode = node.path("progress").path("message");
        ProgressMessage message = messageNode.isMissingNode() || messageNode.isNull()
                ? null
                : ctxt.readTreeAsValue(messageNode, ProgressMessage.class);

        return new ProgressChunk(
                ChoiceNodes.textOrNull(node, "id"),
                node.path("created").asLong(),
                ChoiceNodes.textOrNull(node, "model"),
                message);
    }
}
