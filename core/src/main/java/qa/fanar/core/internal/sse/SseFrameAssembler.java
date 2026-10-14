package qa.fanar.core.internal.sse;

/**
 * Line-oriented accumulator for Server-Sent Events frames.
 *
 * <p>Feed each line from the response body via {@link #accept(String)}; the method returns a
 * non-{@code null} {@link SseFrame} on the blank line that dispatches one, and {@code null}
 * otherwise. The assembler honours the subset of the WHATWG SSE spec that Fanar uses:</p>
 *
 * <ul>
 *   <li>Lines starting with {@code data:} accumulate payload (one newline between lines).</li>
 *   <li>A blank line dispatches a frame. A blank line with no preceding {@code data:} is a no-op.</li>
 *   <li>Lines starting with {@code :} are comments and ignored.</li>
 *   <li>{@code id:} fills the last-event-id buffer, which the next blank line commits as
 *       {@link #lastEventId()} — the WHATWG rule, so an event cut off before its blank line never
 *       moves the resume point. A value containing NUL is ignored; an empty one clears the id. Only
 *       a resumable stream reads it (ADR-032).</li>
 *   <li>Other field names ({@code event}, {@code retry}, anything else) are accepted silently —
 *       Fanar does not use them (ADR-017), but their presence must not break parsing.</li>
 *   <li>Trailing {@code \r} produced by CRLF line endings is stripped.</li>
 * </ul>
 *
 * <p>Not thread-safe: one assembler per connection. Internal.</p>
 *
 * @author Oussama Mahjoub
 */
final class SseFrameAssembler {

    private StringBuilder data;
    private String idBuffer;
    private String lastEventId;
    private boolean inBlock;
    private boolean progressed;

    /** An assembler for a stream's first connection. */
    SseFrameAssembler() {
        this(null);
    }

    /**
     * An assembler for a resumed connection, which continues from the previous one's last event
     * id: WHATWG resets the buffer per connection, which would make an event without an id resume
     * the stream from its first event.
     *
     * @param lastEventId the last event id committed on the previous connection, or {@code null}
     */
    SseFrameAssembler(String lastEventId) {
        this.idBuffer = lastEventId;
        this.lastEventId = lastEventId;
    }

    /**
     * Consume one line from the SSE body.
     *
     * @return a dispatched frame if this line completed one, otherwise {@code null}
     */
    SseFrame accept(String rawLine) {
        String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;

        if (line.isEmpty()) {
            // WHATWG "dispatch the event", step 1: commit the id on every blank line, before the
            // empty-data check — an id-only block moves the resume point too.
            lastEventId = idBuffer;
            progressed |= inBlock;
            inBlock = false;
            if (data == null) {
                return null;
            }
            SseFrame frame = new SseFrame(data.toString());
            data = null;
            return frame;
        }

        inBlock = true;
        if (line.charAt(0) == ':') {
            // Comment — SSE spec says ignore.
            return null;
        }

        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (!value.isEmpty() && value.charAt(0) == ' ') {
            value = value.substring(1);
        }

        if ("id".equals(field)) {
            if (value.indexOf('\u0000') < 0) {
                idBuffer = value.isEmpty() ? null : value;
            }
            return null;
        }

        if (!"data".equals(field)) {
            // event / retry / unknown — tolerated but ignored.
            return null;
        }

        if (data == null) {
            data = new StringBuilder();
        } else {
            data.append('\n');
        }
        data.append(value);
        return null;
    }

    /**
     * The id of the last event this connection completed — committed by the blank line that ended
     * its block — or the id the assembler was seeded with, or {@code null} if neither exists.
     */
    String lastEventId() {
        return lastEventId;
    }

    /**
     * Whether a blank line has completed a non-empty block — an event, a comment such as a
     * keepalive, or a bare field — on this connection: the resumable stream's sign that a response
     * carried something rather than closing empty (ADR-032).
     */
    boolean progressed() {
        return progressed;
    }
}
