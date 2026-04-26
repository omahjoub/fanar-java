package qa.fanar.core.audio;

import java.util.Objects;

/**
 * One time-aligned segment of a transcription, returned in the {@code json}-format variant
 * of the speech-to-text response.
 *
 * <p>Mirrors the {@code STTSegement} schema in the OpenAPI spec (the spec's misspelling is not
 * propagated to the Java type name).</p>
 *
 * @param speaker   speaker label assigned by the server
 * @param startTime start time of this segment, in seconds
 * @param endTime   end time of this segment, in seconds
 * @param duration  duration of the segment, in seconds
 * @param text      transcribed text for this segment
 *
 * @author Oussama Mahjoub
 */
public record Segment(
        String speaker,
        double startTime,
        double endTime,
        double duration,
        String text
) {

    public Segment {
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(text, "text");
    }
}
