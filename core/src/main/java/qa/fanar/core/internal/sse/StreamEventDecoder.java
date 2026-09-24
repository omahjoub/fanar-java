package qa.fanar.core.internal.sse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import qa.fanar.core.FanarException;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.chat.DoneChunk;
import qa.fanar.core.chat.ErrorChunk;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.chat.ToolCallChunk;
import qa.fanar.core.chat.ToolResultChunk;
import qa.fanar.core.internal.transport.ExceptionMapper;
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
 * <p>A deep-research stream, {@link #forDeepResearch}, has no tool events and two rules of its
 * own: top-level {@code report} → {@link ReportChunk}, checked right after {@code progress} and
 * before the {@code usage} / {@code metadata} rule, since a report frame could carry either; and
 * a first choice whose {@code finish_reason} is set to anything but {@code "error"} →
 * {@link DoneChunk}, so the run's terminal frame is the terminal event even when it carries
 * neither {@code usage} (which the spec says is often absent there) nor {@code metadata}. Chat
 * keeps its rule — its stop frames carry content and its terminal chunk carries {@code usage} —
 * which is the one place the two classifiers diverge (ADR-031).</p>
 *
 * <p>Before either classifier runs, a frame whose top-level {@code error} is set is not an event:
 * it is the server reporting that the run failed after the 200 headers went out (the endpoint's
 * own code samples check {@code "error" in chunk}), and decoding it as a chunk would report an
 * SDK decode failure and lose the message. It is routed like an HTTP error envelope instead —
 * by code, then by the status the envelope names — and thrown as the typed {@code FanarException}
 * for the subscriber's {@code onError} (ADR-006, ADR-031). A codec failure of any kind, checked
 * or not, surfaces as {@link FanarTransportException} with the cause attached; a typed
 * {@code FanarException} a codec throws passes through unwrapped.</p>
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
        if (map.get("error") != null) {
            throw ExceptionMapper.mapEnvelope(data);
        }
        Class<? extends E> target = classifier.apply(map);
        return decodeAs(bytes, target, "decode " + target.getSimpleName());
    }

    private <T> T decodeAs(byte[] bytes, Class<T> type, String failureDescription) {
        try {
            return codec.decode(new ByteArrayInputStream(bytes), type);
        } catch (IOException e) {
            throw new FanarTransportException("Failed to " + failureDescription, e);
        } catch (FanarException e) {
            throw e;
        } catch (RuntimeException e) {
            // A codec's own record construction (a flattening deserializer's requireNonNull, say)
            // fails outside Jackson's wrapping; the subscriber still gets the promised type.
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
        if (firstChoice != null) {
            Object finishReason = firstChoice.get("finish_reason");
            if ("error".equals(finishReason)) {
                return ErrorChunk.class;
            }
            if (finishReason != null) {
                return DoneChunk.class;
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
