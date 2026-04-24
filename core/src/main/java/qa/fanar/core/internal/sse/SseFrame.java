package qa.fanar.core.internal.sse;

import java.util.Objects;

/**
 * A dispatched Server-Sent Events frame — the {@code data} payload accumulated between two
 * blank lines on the wire.
 *
 * <p>Fanar emits only {@code data:} lines (ADR-017); the other SSE fields ({@code event},
 * {@code id}, {@code retry}) are accepted silently but not modelled. Internal.</p>
 *
 * @param data joined {@code data:} lines (newline-separated); never {@code null}, may be blank
 */
record SseFrame(String data) {

    SseFrame {
        Objects.requireNonNull(data, "data");
    }
}
