package qa.fanar.json.jackson3;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;

import qa.fanar.core.audio.Segment;
import qa.fanar.core.audio.SpeechToTextResponse;

/**
 * Discriminate the three sealed variants of {@link SpeechToTextResponse} based on which field
 * is present on the wire:
 * <ul>
 *   <li>{@code text} → {@link SpeechToTextResponse.Text}</li>
 *   <li>{@code srt}  → {@link SpeechToTextResponse.Srt}</li>
 *   <li>{@code json} → {@link SpeechToTextResponse.Json} (flattened: the wire's
 *       {@code "json": {"segments": [...]}} unwraps to {@code Json(id, segments)}).</li>
 * </ul>
 */
final class SpeechToTextResponseDeserializer extends StdDeserializer<SpeechToTextResponse> {

    SpeechToTextResponseDeserializer() {
        super(SpeechToTextResponse.class);
    }

    @Override
    public SpeechToTextResponse deserialize(JsonParser parser, DeserializationContext ctxt) {
        JsonNode root = parser.readValueAsTree();
        String id = root.path("id").asString();

        if (root.has("text")) {
            return new SpeechToTextResponse.Text(id, root.path("text").asString());
        }
        if (root.has("srt")) {
            return new SpeechToTextResponse.Srt(id, root.path("srt").asString());
        }
        if (root.has("json")) {
            JsonNode segmentsNode = root.path("json").path("segments");
            List<Segment> segments = new ArrayList<>(segmentsNode.size());
            for (JsonNode seg : segmentsNode) {
                segments.add(new Segment(
                        seg.path("speaker").asString(),
                        seg.path("start_time").asDouble(),
                        seg.path("end_time").asDouble(),
                        seg.path("duration").asDouble(),
                        seg.path("text").asString()));
            }
            return new SpeechToTextResponse.Json(id, segments);
        }

        throw MismatchedInputException.from(parser, handledType(),
                "SpeechToTextResponse must have one of `text`, `srt`, or `json`; got "
                        + root.propertyNames());
    }
}
