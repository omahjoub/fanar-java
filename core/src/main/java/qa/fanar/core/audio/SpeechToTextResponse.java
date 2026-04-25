package qa.fanar.core.audio;

import java.util.List;
import java.util.Objects;

/**
 * Sealed response from {@code POST /v1/audio/transcriptions}. The variant the server picks
 * matches the {@link TranscriptionRequest#format()} that was sent.
 *
 * <p>Callers pattern-match to extract content:</p>
 *
 * <pre>{@code
 * SpeechToTextResponse r = client.audio().transcribe(req);
 * String body = switch (r) {
 *     case SpeechToTextResponse.Text t -> t.text();
 *     case SpeechToTextResponse.Srt s  -> s.srt();
 *     case SpeechToTextResponse.Json j -> j.segments().toString();
 * };
 * }</pre>
 *
 * <p>The OpenAPI spec models this as an {@code anyOf} of three top-level types
 * ({@code SpeechToTextResponseWithText} / {@code WithSRT} / {@code WithJson}). The SDK's
 * sealed-interface form gives you exhaustive switch coverage. The {@code Json} variant
 * <em>flattens</em> the wire's nested {@code "json": {"segments": [...]}} into the record's
 * {@link Json#segments()} accessor — the JSON adapter does that unwrap during decode.</p>
 */
public sealed interface SpeechToTextResponse {

    /** Unique identifier for the transcription. Same field on every variant. */
    String id();

    /** Plain-text transcription — produced when {@code format=text} (the default). */
    record Text(String id, String text) implements SpeechToTextResponse {
        public Text {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(text, "text");
        }
    }

    /** SubRip subtitle string — produced when {@code format=srt}. */
    record Srt(String id, String srt) implements SpeechToTextResponse {
        public Srt {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(srt, "srt");
        }
    }

    /** Time-aligned segments — produced when {@code format=json}. */
    record Json(String id, List<Segment> segments) implements SpeechToTextResponse {
        public Json {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(segments, "segments");
            segments = List.copyOf(segments);
        }
    }
}
