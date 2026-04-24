package qa.fanar.core.internal.sse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import qa.fanar.core.FanarTransportException;
import qa.fanar.core.chat.DoneChunk;
import qa.fanar.core.chat.ErrorChunk;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.chat.ToolCallChunk;
import qa.fanar.core.chat.ToolResultChunk;
import qa.fanar.core.spi.FanarJsonCodec;

/**
 * Decodes an SSE {@code data:} payload into the right {@link StreamEvent} subtype.
 *
 * <p>Shape discrimination per ADR-017:</p>
 * <ol>
 *   <li>Top-level {@code progress} → {@link ProgressChunk}.</li>
 *   <li>Top-level {@code usage} (non-null) or {@code metadata} → {@link DoneChunk}.</li>
 *   <li>First choice's {@code finish_reason == "error"} → {@link ErrorChunk}.</li>
 *   <li>First choice's {@code delta.tool_calls} → {@link ToolCallChunk}.</li>
 *   <li>First choice's {@code delta.tool_result} → {@link ToolResultChunk}.</li>
 *   <li>Fallback → {@link TokenChunk} (the common case).</li>
 * </ol>
 *
 * <p>Decoding is two-pass: a first pass into {@code Map} to inspect shape, then a second pass
 * into the target record. The input is small (one SSE frame) so the cost is negligible.</p>
 *
 * <p>Internal (ADR-018).</p>
 */
final class StreamEventDecoder {

    /**
     * OpenAI-compatible terminal sentinel emitted by some gateways proxying Fanar. The data
     * payload is the literal string {@code [DONE]}. Tolerated defensively even though the
     * typed {@code DoneChunk} is the authoritative end-of-stream signal.
     */
    static final String DONE_SENTINEL = "[DONE]";

    private final FanarJsonCodec codec;

    StreamEventDecoder(FanarJsonCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * Decode a frame into a {@link StreamEvent}, or return {@code null} to signal that the
     * frame is not a real event (blank data, {@code [DONE]} sentinel).
     */
    StreamEvent decode(SseFrame frame) {
        String data = frame.data();
        String trimmed = data.strip();
        if (trimmed.isEmpty() || DONE_SENTINEL.equals(trimmed)) {
            return null;
        }

        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        Map<?, ?> map = decodeAs(bytes, Map.class, "parse SSE payload");
        Class<? extends StreamEvent> target = classify(map);
        return decodeAs(bytes, target, "decode " + target.getSimpleName());
    }

    private <T> T decodeAs(byte[] bytes, Class<T> type, String failureDescription) {
        try {
            return codec.decode(new ByteArrayInputStream(bytes), type);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to " + failureDescription, e);
        }
    }

    private static Class<? extends StreamEvent> classify(Map<?, ?> map) {
        if (map.get("progress") != null) {
            return ProgressChunk.class;
        }
        if (map.get("usage") != null || map.get("metadata") != null) {
            return DoneChunk.class;
        }

        Map<?, ?> firstChoice = firstChoice(map);
        if (firstChoice != null) {
            if ("error".equals(firstChoice.get("finish_reason"))) {
                return ErrorChunk.class;
            }
            Object delta = firstChoice.get("delta");
            if (delta instanceof Map<?, ?> d) {
                if (d.get("tool_calls") != null) {
                    return ToolCallChunk.class;
                }
                if (d.get("tool_result") != null) {
                    return ToolResultChunk.class;
                }
            }
        }
        return TokenChunk.class;
    }

    private static Map<?, ?> firstChoice(Map<?, ?> map) {
        Object choices = map.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> first) {
            return first;
        }
        return null;
    }
}
