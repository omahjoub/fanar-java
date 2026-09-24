package qa.fanar.core.internal.sse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import qa.fanar.core.FanarTransportException;
import qa.fanar.core.chat.DoneChunk;
import qa.fanar.core.chat.ErrorChunk;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.chat.ToolCallChunk;
import qa.fanar.core.chat.ToolResultChunk;
import qa.fanar.core.sadiq.DeepResearchEvent;
import qa.fanar.core.sadiq.ReportChunk;
import qa.fanar.core.spi.FanarJsonCodec;

/**
 * Decodes an SSE {@code data:} payload into the right event record for one endpoint's stream.
 *
 * <p>Every Fanar stream uses the same frame format, but each endpoint emits its own set of
 * shapes, so the decoder is built per endpoint with a classifier that maps a frame's top-level
 * keys to the target record (ADR-017, ADR-031). Shape discrimination for a chat stream,
 * {@link #forChat}:</p>
 * <ol>
 *   <li>Top-level {@code progress} → {@link ProgressChunk}.</li>
 *   <li>Top-level {@code usage} (non-null) or {@code metadata} → {@link DoneChunk}.</li>
 *   <li>First choice's {@code finish_reason == "error"} → {@link ErrorChunk}.</li>
 *   <li>First choice's {@code delta.tool_calls} → {@link ToolCallChunk}.</li>
 *   <li>First choice's {@code delta.tool_result} → {@link ToolResultChunk}.</li>
 *   <li>Fallback → {@link TokenChunk} (the common case).</li>
 * </ol>
 *
 * <p>A deep-research stream, {@link #forDeepResearch}, has no tool events and one shape of its
 * own: top-level {@code report} → {@link ReportChunk}, checked right after {@code progress} and
 * before the {@code usage} / {@code metadata} rule, since a report frame could carry either.
 * Otherwise the same rules apply.</p>
 *
 * <p>Decoding is two-pass: a first pass into {@code Map} to inspect shape, then a second pass
 * into the target record. The input is small (one SSE frame) so the cost is negligible.</p>
 *
 * <p>Internal (ADR-018).</p>
 *
 * @param <E> the endpoint's event union
 *
 * @author Oussama Mahjoub
 */
final class StreamEventDecoder<E> {

    /**
     * OpenAI-compatible terminal sentinel emitted by some gateways proxying Fanar. The data
     * payload is the literal string {@code [DONE]}. Tolerated defensively even though the
     * typed {@code DoneChunk} is the authoritative end-of-stream signal.
     */
    static final String DONE_SENTINEL = "[DONE]";

    private final FanarJsonCodec codec;
    private final Function<Map<?, ?>, Class<? extends E>> classifier;

    private StreamEventDecoder(FanarJsonCodec codec, Function<Map<?, ?>, Class<? extends E>> classifier) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.classifier = classifier;
    }

    /** The decoder for {@code POST /v1/chat/completions} streams. */
    static StreamEventDecoder<StreamEvent> forChat(FanarJsonCodec codec) {
        return new StreamEventDecoder<>(codec, StreamEventDecoder::classifyChat);
    }

    /** The decoder for {@code POST /v1/sadiq/deep-research} streams. */
    static StreamEventDecoder<DeepResearchEvent> forDeepResearch(FanarJsonCodec codec) {
        return new StreamEventDecoder<>(codec, StreamEventDecoder::classifyDeepResearch);
    }

    /**
     * Decode a frame into an event, or return {@code null} to signal that the frame is not a
     * real event (blank data, {@code [DONE]} sentinel).
     */
    E decode(SseFrame frame) {
        String data = frame.data();
        String trimmed = data.strip();
        if (trimmed.isEmpty() || DONE_SENTINEL.equals(trimmed)) {
            return null;
        }

        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        Map<?, ?> map = decodeAs(bytes, Map.class, "parse SSE payload");
        Class<? extends E> target = classifier.apply(map);
        return decodeAs(bytes, target, "decode " + target.getSimpleName());
    }

    private <T> T decodeAs(byte[] bytes, Class<T> type, String failureDescription) {
        try {
            return codec.decode(new ByteArrayInputStream(bytes), type);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to " + failureDescription, e);
        }
    }

    private static Class<? extends StreamEvent> classifyChat(Map<?, ?> map) {
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

    private static Class<? extends DeepResearchEvent> classifyDeepResearch(Map<?, ?> map) {
        if (map.get("progress") != null) {
            return ProgressChunk.class;
        }
        if (map.get("report") != null) {
            return ReportChunk.class;
        }
        if (map.get("usage") != null || map.get("metadata") != null) {
            return DoneChunk.class;
        }
        Map<?, ?> firstChoice = firstChoice(map);
        if (firstChoice != null && "error".equals(firstChoice.get("finish_reason"))) {
            return ErrorChunk.class;
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
