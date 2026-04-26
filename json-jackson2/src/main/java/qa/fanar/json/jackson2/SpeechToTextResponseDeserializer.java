package qa.fanar.json.jackson2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import qa.fanar.core.audio.Segment;
import qa.fanar.core.audio.SpeechToTextResponse;

/**
 * Discriminate the three sealed variants of {@link SpeechToTextResponse} based on which field
 * is present on the wire:
 * <ul>
 *   <li>{@code text} → {@link SpeechToTextResponse.Text}</li>
 *   <li>{@code srt}  → {@link SpeechToTextResponse.Srt}</li>
 *   <li>{@code json} → {@link SpeechToTextResponse.Json} (flattened).</li>
 * </ul>
 *
 * @author Oussama Mahjoub
 */
final class SpeechToTextResponseDeserializer extends StdDeserializer<SpeechToTextResponse> {

    SpeechToTextResponseDeserializer() {
        super(SpeechToTextResponse.class);
    }

    @Override
    public SpeechToTextResponse deserialize(JsonParser parser, DeserializationContext ctxt)
            throws IOException {
        JsonNode root = parser.readValueAsTree();
        String id = root.path("id").asText();

        if (root.has("text")) {
            return new SpeechToTextResponse.Text(id, root.path("text").asText());
        }
        if (root.has("srt")) {
            return new SpeechToTextResponse.Srt(id, root.path("srt").asText());
        }
        if (root.has("json")) {
            JsonNode segmentsNode = root.path("json").path("segments");
            List<Segment> segments = new ArrayList<>(segmentsNode.size());
            for (JsonNode seg : segmentsNode) {
                segments.add(new Segment(
                        seg.path("speaker").asText(),
                        seg.path("start_time").asDouble(),
                        seg.path("end_time").asDouble(),
                        seg.path("duration").asDouble(),
                        seg.path("text").asText()));
            }
            return new SpeechToTextResponse.Json(id, segments);
        }

        throw MismatchedInputException.from(parser, handledType(),
                "SpeechToTextResponse must have one of `text`, `srt`, or `json`; got "
                        + root.fieldNames());
    }
}
