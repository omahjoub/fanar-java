package qa.fanar.core.internal.sse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarTransportException;
import qa.fanar.core.chat.ChoiceError;
import qa.fanar.core.chat.ChoiceFinal;
import qa.fanar.core.chat.ChoiceToken;
import qa.fanar.core.chat.ChoiceToolCall;
import qa.fanar.core.chat.ChoiceToolResult;
import qa.fanar.core.chat.CompletionUsage;
import qa.fanar.core.chat.DoneChunk;
import qa.fanar.core.chat.ErrorChunk;
import qa.fanar.core.chat.FunctionData;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.ProgressMessage;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.chat.ToolCallChunk;
import qa.fanar.core.chat.ToolCallData;
import qa.fanar.core.chat.ToolResultChunk;
import qa.fanar.core.chat.ToolResultData;
import qa.fanar.core.sadiq.DeepResearchEvent;
import qa.fanar.core.sadiq.DeepResearchReport;
import qa.fanar.core.sadiq.ReportChunk;
import qa.fanar.core.spi.FanarJsonCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import qa.fanar.core.FanarAuthorizationException;
import qa.fanar.core.FanarException;
import qa.fanar.core.FanarInternalServerException;
import qa.fanar.core.FanarUnexpectedServerException;

class StreamEventDecoderTest {

    @Test
    void blankDataReturnsNull() {
        StreamEventDecoder<StreamEvent> decoder = StreamEventDecoder.forChat(neverCalled());
        assertNull(decoder.decode(new SseFrame("")));
        assertNull(decoder.decode(new SseFrame("   \n  ")));
    }

    @Test
    void doneSentinelReturnsNull() {
        StreamEventDecoder<StreamEvent> decoder = StreamEventDecoder.forChat(neverCalled());
        assertNull(decoder.decode(new SseFrame("[DONE]")));
        assertNull(decoder.decode(new SseFrame("  [DONE]  ")));
    }

    @Test
    void routesProgressShapeToProgressChunk() {
        ProgressChunk expected = new ProgressChunk(
                "c_1", 0L, "Fanar", new ProgressMessage("searching", "البحث"));
        FakeCodec codec = new FakeCodec(
                Map.of("id", "c_1", "progress", Map.of("message", Map.of("en", "searching", "ar", "البحث"))),
                expected);

        StreamEvent event = StreamEventDecoder.forChat(codec).decode(new SseFrame("{\"progress\":{}}"));
        assertSame(expected, event);
        assertInstanceOf(ProgressChunk.class, event);
    }

    @Test
    void routesUsageShapeToDoneChunk() {
        DoneChunk expected = new DoneChunk("c_1", 0L, "Fanar", List.of(
                new ChoiceFinal(0, "stop", List.of())),
                new CompletionUsage(0, 0, 0, null, null, null, null), Map.of());
        FakeCodec codec = new FakeCodec(
                Map.of("usage", Map.of("total_tokens", 10), "choices", List.of()),
                expected);

        StreamEvent event = StreamEventDecoder.forChat(codec).decode(new SseFrame("{\"usage\":{}}"));
        assertInstanceOf(DoneChunk.class, event);
    }

    @Test
    void routesMetadataShapeToDoneChunk() {
        DoneChunk expected = new DoneChunk(
                "c_1", 0L, "Fanar", List.of(new ChoiceFinal(0, "stop", List.of())),
                null, Map.of("trace", "abc"));
        FakeCodec codec = new FakeCodec(
                Map.of("metadata", Map.of("trace", "abc"), "choices", List.of()),
                expected);

        StreamEvent event = StreamEventDecoder.forChat(codec).decode(new SseFrame("{\"metadata\":{}}"));
        assertInstanceOf(DoneChunk.class, event);
    }

    @Test
    void routesFinishReasonErrorToErrorChunk() {
        ErrorChunk expected = new ErrorChunk("c_1", 0L, "Fanar", List.of(
                new ChoiceError(0, "error", "something went wrong")));
        FakeCodec codec = new FakeCodec(
                Map.of("choices", List.of(
                        Map.of("finish_reason", "error",
                                "delta", Map.of("content", "something went wrong")))),
                expected);

        StreamEvent event = StreamEventDecoder.forChat(codec).decode(new SseFrame("{\"choices\":[...]}"));
        assertInstanceOf(ErrorChunk.class, event);
    }

    @Test
    void routesDeltaToolCallsToToolCallChunk() {
        ToolCallChunk expected = new ToolCallChunk("c_1", 0L, "Fanar", List.of(
                new ChoiceToolCall(0, null, List.of(
                        new ToolCallData(0, "t_1", "function", new FunctionData("f", "{}"))))));
        FakeCodec codec = new FakeCodec(
                Map.of("choices", List.of(
                        Map.of("delta", Map.of("tool_calls", List.of(Map.of("id", "t_1")))))),
                expected);

        StreamEvent event = StreamEventDecoder.forChat(codec).decode(new SseFrame("{\"choices\":[...]}"));
        assertInstanceOf(ToolCallChunk.class, event);
    }

    @Test
    void routesDeltaToolResultToToolResultChunk() {
        ToolResultChunk expected = new ToolResultChunk("c_1", 0L, "Fanar", List.of(
                new ChoiceToolResult(0, null, new ToolResultData(
                        "t_1", "function", Map.of(), "ok", Map.of(), false))));
        FakeCodec codec = new FakeCodec(
                Map.of("choices", List.of(
                        Map.of("delta", Map.of("tool_result", Map.of("id", "t_1"))))),
                expected);

        StreamEvent event = StreamEventDecoder.forChat(codec).decode(new SseFrame("{\"choices\":[...]}"));
        assertInstanceOf(ToolResultChunk.class, event);
    }

    @Test
    void fallsBackToTokenChunk() {
        TokenChunk expected = new TokenChunk("c_1", 0L, "Fanar", List.of(
                new ChoiceToken(0, null, "hello")));
        FakeCodec codec = new FakeCodec(
                Map.of("choices", List.of(Map.of("delta", Map.of("content", "hello")))),
                expected);

        StreamEvent event = StreamEventDecoder.forChat(codec).decode(new SseFrame("{\"choices\":[...]}"));
        assertInstanceOf(TokenChunk.class, event);
    }

    @Test
    void fallsBackToTokenChunkWhenChoicesIsEmpty() {
        TokenChunk expected = new TokenChunk("c_1", 0L, "Fanar", List.of());
        FakeCodec codec = new FakeCodec(Map.of("choices", List.of()), expected);

        StreamEvent event = StreamEventDecoder.forChat(codec).decode(new SseFrame("{\"choices\":[]}"));
        assertInstanceOf(TokenChunk.class, event);
    }

    @Test
    void fallsBackToTokenChunkWhenChoicesIsMissing() {
        TokenChunk expected = new TokenChunk("c_1", 0L, "Fanar", List.of());
        FakeCodec codec = new FakeCodec(Map.of("id", "c_1"), expected);

        StreamEvent event = StreamEventDecoder.forChat(codec).decode(new SseFrame("{\"id\":\"c_1\"}"));
        assertInstanceOf(TokenChunk.class, event);
    }

    @Test
    void fallsBackToTokenChunkWhenFirstChoiceIsNotAMap() {
        TokenChunk expected = new TokenChunk("c_1", 0L, "Fanar", List.of());
        FakeCodec codec = new FakeCodec(Map.of("choices", List.of("not-an-object")), expected);

        StreamEvent event = StreamEventDecoder.forChat(codec).decode(new SseFrame("{\"choices\":[\"x\"]}"));
        assertInstanceOf(TokenChunk.class, event);
    }

    @Test
    void fallsBackToTokenChunkWhenDeltaIsNotAMap() {
        // `delta` is present but not an object (e.g., a stringified delta or null-like).
        // The shape router should tolerate and fall through to the TokenChunk default.
        TokenChunk expected = new TokenChunk("c_1", 0L, "Fanar", List.of());
        FakeCodec codec = new FakeCodec(
                Map.of("choices", List.of(Map.of("delta", "not-an-object"))),
                expected);

        StreamEvent event = StreamEventDecoder.forChat(codec).decode(new SseFrame("{\"choices\":[...]}"));
        assertInstanceOf(TokenChunk.class, event);
    }

    @Test
    void firstPassParseFailureWrapsAsTransportException() {
        FanarJsonCodec throwing = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException { throw new IOException("bad json"); }
            public void encode(OutputStream s, Object v) { /* unused */ }
        };
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> StreamEventDecoder.forChat(throwing).decode(new SseFrame("garbage")));
        assertTrue(ex.getMessage().contains("parse SSE payload"));
    }

    @Test
    void secondPassDecodeFailureWrapsAsTransportException() {
        FanarJsonCodec twoPhase = new FanarJsonCodec() {
            private int call;
            public <T> T decode(InputStream s, Class<T> t) throws IOException {
                s.readAllBytes();
                if (call++ == 0) return t.cast(Map.of());
                throw new IOException("decode phase 2");
            }
            public void encode(OutputStream s, Object v) { /* unused */ }
        };
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> StreamEventDecoder.forChat(twoPhase).decode(new SseFrame("{}")));
        assertTrue(ex.getMessage().contains("decode TokenChunk"));
    }

    @Test
    void rejectsNullCodec() {
        assertThrows(NullPointerException.class, () -> StreamEventDecoder.forChat(null));
    }

    @Test
    void doneSentinelConstantMatchesLiteral() {
        assertEquals("[DONE]", StreamEventDecoder.DONE_SENTINEL);
    }

    // --- the deep-research classifier (ADR-031)

    private static final DeepResearchReport REPORT =
            new DeepResearchReport(null, null, "Title", null, null, null, null, null, null);

    @Test
    void deepResearchRoutesReportShapeToReportChunk() {
        ReportChunk expected = new ReportChunk("c_1", 0L, "Fanar-Sadiq-2", REPORT);
        FakeCodec codec = new FakeCodec(Map.of("report", Map.of("title", "Title")), expected);
        DeepResearchEvent event = StreamEventDecoder.forDeepResearch(codec).decode(new SseFrame("{\"report\":{}}"));
        assertSame(expected, event);
    }

    @Test
    void deepResearchRoutesReportBeforeMetadata() {
        // A report frame that also carried run metadata is still the report, not the terminal chunk.
        ReportChunk expected = new ReportChunk("c_1", 0L, "Fanar-Sadiq-2", REPORT);
        FakeCodec codec = new FakeCodec(Map.of("report", Map.of(), "metadata", Map.of()), expected);
        assertSame(expected, StreamEventDecoder.forDeepResearch(codec).decode(new SseFrame("{}")));
    }

    @Test
    void deepResearchRoutesProgressShapeToProgressChunkEvenWithoutAModel() {
        ProgressChunk expected = new ProgressChunk("c_1", 0L, null, new ProgressMessage("planning", "تخطيط"));
        FakeCodec codec = new FakeCodec(Map.of("progress", Map.of()), expected);
        assertSame(expected, StreamEventDecoder.forDeepResearch(codec).decode(new SseFrame("{\"progress\":{}}")));
    }

    @Test
    void deepResearchRoutesMetadataAndUsageShapesToDoneChunk() {
        DoneChunk expected = new DoneChunk("c_1", 0L, "Fanar-Sadiq-2", List.of(), null, Map.of("depth", "quick"));
        assertSame(expected, StreamEventDecoder.forDeepResearch(
                new FakeCodec(Map.of("metadata", Map.of()), expected)).decode(new SseFrame("{}")));
        assertSame(expected, StreamEventDecoder.forDeepResearch(
                new FakeCodec(Map.of("usage", Map.of()), expected)).decode(new SseFrame("{}")));
    }

    @Test
    void deepResearchRoutesFinishReasonErrorToErrorChunk() {
        ErrorChunk expected = new ErrorChunk("c_1", 0L, "Fanar-Sadiq-2", List.of(new ChoiceError(0, null, "boom")));
        FakeCodec codec = new FakeCodec(Map.of("choices", List.of(Map.of("finish_reason", "error"))), expected);
        assertSame(expected, StreamEventDecoder.forDeepResearch(codec).decode(new SseFrame("{}")));
    }

    @Test
    void deepResearchFallsBackToTokenChunk() {
        TokenChunk expected = new TokenChunk("c_1", 0L, "Fanar-Sadiq-2", List.of(new ChoiceToken(0, null, "draft")));
        assertSame(expected, StreamEventDecoder.forDeepResearch(
                new FakeCodec(Map.of("choices", List.of(Map.of("index", 0))), expected))
                .decode(new SseFrame("{}")), "a choice without a finish reason is a delta");
        assertSame(expected, StreamEventDecoder.forDeepResearch(
                new FakeCodec(Map.of("choices", List.of()), expected)).decode(new SseFrame("{}")));
        assertSame(expected, StreamEventDecoder.forDeepResearch(
                new FakeCodec(Map.of(), expected)).decode(new SseFrame("{}")));
    }

    @Test
    void deepResearchRoutesAFinishedChoiceToDoneChunkEvenWithoutMetadataOrUsage() {
        // The run's terminal frame is the terminal event even when the server sends neither
        // usage (often absent, per the spec) nor metadata — chat's classifier is unchanged.
        DoneChunk expected = new DoneChunk("c_1", 0L, "Fanar-Sadiq-2", List.of(), null, null);
        assertSame(expected, StreamEventDecoder.forDeepResearch(
                new FakeCodec(Map.of("choices", List.of(Map.of("finish_reason", "stop"))), expected))
                .decode(new SseFrame("{}")));
        TokenChunk token = new TokenChunk("c_1", 0L, "Fanar", List.of());
        assertSame(token, StreamEventDecoder.forChat(
                new FakeCodec(Map.of("choices", List.of(Map.of("finish_reason", "stop"))), token))
                .decode(new SseFrame("{}")), "chat still treats a stop frame without usage as a token");
    }

    @Test
    void deepResearchSkipsBlankAndSentinelFrames() {
        StreamEventDecoder<DeepResearchEvent> decoder = StreamEventDecoder.forDeepResearch(neverCalled());
        assertNull(decoder.decode(new SseFrame("")));
        assertNull(decoder.decode(new SseFrame("[DONE]")));
    }

    @Test
    void chatDecoderStillTreatsAReportFrameAsAToken() {
        // Chat never emits a report; its classifier is unchanged, so the frame falls through.
        TokenChunk expected = new TokenChunk("c_1", 0L, "Fanar", List.of());
        FakeCodec codec = new FakeCodec(Map.of("report", Map.of()), expected);
        assertSame(expected, StreamEventDecoder.forChat(codec).decode(new SseFrame("{\"report\":{}}")));
    }

    @Test
    void deepResearchDecoderRejectsNullCodec() {
        assertThrows(NullPointerException.class, () -> StreamEventDecoder.forDeepResearch(null));
    }

    // --- Fakes

    private static FanarJsonCodec neverCalled() {
        return new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) { throw new AssertionError("codec must not be called"); }
            public void encode(OutputStream s, Object v) { throw new AssertionError("codec must not be called"); }
        };
    }

    /** Returns the shape map on the first call, then the typed event on subsequent calls. */
    private static final class FakeCodec implements FanarJsonCodec {
        private final Map<?, ?> shape;
        private final Object typed;
        private int call;

        FakeCodec(Map<?, ?> shape, Object typed) {
            this.shape = shape;
            this.typed = typed;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T decode(InputStream stream, Class<T> type) throws IOException {
            stream.readAllBytes();
            call++;
            if (call == 1) {
                // First call — classifier asks for Map.
                return (T) shape;
            }
            return type.cast(typed);
        }

        @Override
        public void encode(OutputStream stream, Object value) {
            throw new AssertionError("encode must not be called");
        }
    }

    /** Ensures ByteArrayInputStream shape argument is used correctly. */
    @Test
    void decoderAlwaysReadsFromByteArrayInputStream() throws IOException {
        TokenChunk expected = new TokenChunk("c_1", 0L, "Fanar", List.of());
        StreamEventDecoder<StreamEvent> decoder = StreamEventDecoder.forChat(new FakeCodec(Map.of("choices", List.of()), expected));
        // The frame data should be UTF-8 encoded once and fed as bytes.
        String arabic = "{\"ar\":\"مرحبا\"}"; // ensures multi-byte UTF-8 flows correctly
        new ByteArrayInputStream(arabic.getBytes(StandardCharsets.UTF_8)).close();
        assertInstanceOf(TokenChunk.class, decoder.decode(new SseFrame(arabic)));
    }

    // --- in-stream error envelopes and codec failures (ADR-031)

    @Test
    void aFrameWithATopLevelErrorObjectIsThrownAsTheTypedExceptionOnBothClassifiers() {
        String frame = "{\"id\":\"c\",\"created\":2,\"model\":\"m\","
                + "\"error\":{\"code\":\"internal_server_error\",\"message\":\"the run blew up\"}}";
        Map<String, Object> shape = Map.of("error", Map.of("code", "internal_server_error"));
        FanarInternalServerException research = assertThrows(FanarInternalServerException.class,
                () -> StreamEventDecoder.forDeepResearch(new FakeCodec(shape, null)).decode(new SseFrame(frame)));
        assertEquals("the run blew up", research.getMessage());
        assertThrows(FanarInternalServerException.class,
                () -> StreamEventDecoder.forChat(new FakeCodec(shape, null)).decode(new SseFrame(frame)));
    }

    @Test
    void aTopLevelErrorOfAnyTypeIsAnError() {
        // The samples check `"error" in chunk`; a string value must not fall through to TokenChunk.
        FanarException ex = assertThrows(FanarException.class, () -> StreamEventDecoder.forDeepResearch(
                new FakeCodec(Map.of("error", "quota exhausted"), null)).decode(new SseFrame("{\"error\":\"quota exhausted\"}")));
        assertInstanceOf(FanarUnexpectedServerException.class, ex);
        assertTrue(ex.getMessage().contains("quota exhausted"), ex.getMessage());
    }

    @Test
    void aNullErrorMemberIsNotAnError() {
        TokenChunk expected = new TokenChunk("c_1", 0L, "Fanar", List.of());
        Map<String, Object> shape = new java.util.HashMap<>();
        shape.put("error", null);
        assertSame(expected, StreamEventDecoder.forDeepResearch(new FakeCodec(shape, expected)).decode(new SseFrame("{\"error\":null}")));
    }

    @Test
    void aCodecRuntimeFailureSurfacesAsTransportExceptionWithTheCause() {
        // A flattening deserializer's own requireNonNull escapes Jackson's wrapping as a raw NPE.
        NullPointerException npe = new NullPointerException("message");
        FanarJsonCodec codec = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) { throw npe; }
            public void encode(OutputStream s, Object v) { /* unused */ }
        };
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> StreamEventDecoder.forChat(codec).decode(new SseFrame("{}")));
        assertSame(npe, ex.getCause());
        assertTrue(ex.getMessage().startsWith("Failed to parse SSE payload"), ex.getMessage());
    }

    @Test
    void aTypedExceptionFromTheCodecPassesThroughUnwrapped() {
        FanarAuthorizationException typed = new FanarAuthorizationException("nope");
        FanarJsonCodec codec = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) { throw typed; }
            public void encode(OutputStream s, Object v) { /* unused */ }
        };
        assertSame(typed, assertThrows(FanarAuthorizationException.class,
                () -> StreamEventDecoder.forDeepResearch(codec).decode(new SseFrame("{}"))));
    }
}
