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
 *   <li>Lines with unknown field names ({@code event}, {@code id}, {@code retry}, anything else)
 *       are accepted silently — Fanar does not use them today (ADR-017), but their presence must
 *       not break parsing.</li>
 *   <li>Trailing {@code \r} produced by CRLF line endings is stripped.</li>
 * </ul>
 *
 * <p>Not thread-safe: one assembler per connection. Internal.</p>
 */
final class SseFrameAssembler {

    private StringBuilder data;

    /**
     * Consume one line from the SSE body.
     *
     * @return a dispatched frame if this line completed one, otherwise {@code null}
     */
    SseFrame accept(String rawLine) {
        String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;

        if (line.isEmpty()) {
            if (data == null) {
                return null;
            }
            SseFrame frame = new SseFrame(data.toString());
            data = null;
            return frame;
        }

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

        if (!"data".equals(field)) {
            // event / id / retry / unknown — tolerated but ignored.
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
}
