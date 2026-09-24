package qa.fanar.e2e.sadiq;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import qa.fanar.core.FanarClient;
import qa.fanar.core.FanarInternalServerException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.DoneChunk;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.sadiq.DeepResearchDepth;
import qa.fanar.core.sadiq.DeepResearchEvent;
import qa.fanar.core.sadiq.DeepResearchReport;
import qa.fanar.core.sadiq.DeepResearchRequest;
import qa.fanar.core.sadiq.ReportChunk;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;
import qa.fanar.testsupport.CollectingSubscriber;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spec's own deep-research stream, decoded end to end by the <em>real</em> JSON adapters:
 * {@code FanarClient.builder()} with Jackson 2 and with Jackson 3 → {@code sadiq()} → interceptor
 * chain → JDK transport → a scripted server replaying the endpoint's {@code text/event-stream}
 * example verbatim. Core's own seam test proves the same wiring with a hand-rolled codec (core
 * has no Jackson on its test classpath); this class is where the classifier, the flattening
 * deserializers and the records meet the wire as shipped (ADR-031). Offline, no key needed.
 */
@Tag("integration")
class DeepResearchWireIntegrationTest {

    /** The endpoint's SSE example from api-spec/openapi.json, trimmed to one token delta. */
    private static final String SPEC_EXAMPLE = "data: {\"id\":\"chatcmpl-a46c47\",\"object\":\"chat.completion.chunk\",\"created\":1789987956,\"model\":null,\"progress\":{\"message\":{\"en\":\"Analyzing topic and planning report structure...\",\"ar\":\"تحليل الموضوع وتخطيط هيكل التقرير...\"}}}\n\n"
            + "data: {\"id\":\"chatcmpl-a46c47\",\"object\":\"chat.completion.chunk\",\"created\":1789987961,\"model\":\"Fanar-Sadiq-2\",\"progress\":{\"message\":{\"en\":\"Researching: الأساس الديني لطلب العلم...\",\"ar\":\"البحث في: الأساس الديني لطلب العلم...\"}}}\n\n"
            + "data: {\"id\":\"chatcmpl-a46c47\",\"object\":\"chat.completion.chunk\",\"created\":1789988100,\"model\":\"Fanar-Sadiq-2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"يؤكد \"},\"finish_reason\":null}]}\n\n"
            + "data: {\"id\":\"chatcmpl-a46c47\",\"object\":\"chat.completion.chunk\",\"created\":1789988223,\"model\":\"Fanar-Sadiq-2\",\"report\":{\"title\":\"أهمية طلب العلم في الإسلام\",\"sections\":[{\"heading\":\"الأساس الديني لطلب العلم\",\"mode\":\"general\",\"content\":\"## الأساس\",\"cited_sources\":[{\"source\":\"https://islamweb.net/ar/article/228949/\",\"citation_tag\":\"[إسلام ويب:228949]\",\"quote\":\"العِلْمُ نورٌ\",\"was_cited\":true}]}],\"sources\":[{\"source\":\"https://islamweb.net/ar/article/228949/\",\"citation_tag\":\"[إسلام ويب:228949]\",\"quote\":\"العِلْمُ نورٌ\",\"was_cited\":true}]}}\n\n"
            + "data: {\"id\":\"chatcmpl-a46c47\",\"object\":\"chat.completion.chunk\",\"created\":1789988223,\"model\":\"Fanar-Sadiq-2\",\"choices\":[{\"index\":0,\"delta\":{\"references\":null},\"finish_reason\":\"stop\"}],\"metadata\":{\"depth\":\"quick\",\"elapsed_seconds\":267.32,\"total_sources\":28,\"web_search_used\":false}}\n\n"
            + "data: [DONE]\n\n";

    private static final String FAILED_RUN = "data: {\"id\":\"c\",\"created\":1,\"model\":null,\"progress\":{\"message\":{\"en\":\"Analyzing\",\"ar\":\"تحليل\"}}}\n\n"
            + "data: {\"id\":\"c\",\"created\":2,\"model\":\"Fanar-Sadiq-2\",\"error\":{\"code\":\"internal_server_error\",\"message\":\"the run blew up\",\"status\":500}}\n\n"
            + "data: [DONE]\n\n";

    private static final String BARE_TERMINAL_RUN = "data: {\"id\":\"c\",\"created\":3,\"model\":\"Fanar-Sadiq-2\",\"report\":{\"title\":\"t\"}}\n\n"
            + "data: {\"id\":\"c\",\"created\":4,\"model\":null,\"choices\":[{\"index\":0,\"delta\":{\"references\":null},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("the spec's example stream decodes into its five event kinds, first progress without a model")
    void theSpecExampleDecodesEndToEnd(FanarJsonCodec codec) throws Exception {
        server.enqueue(Reply.sse(SPEC_EXAMPLE));
        CollectingSubscriber<DeepResearchEvent> sub = CollectingSubscriber.unbounded();

        List<DeepResearchEvent> events;
        try (FanarClient client = client(codec)) {
            client.sadiq().deepResearchStream(new DeepResearchRequest(
                    ChatModel.FANAR_SADIQ_2, "The importance of seeking knowledge in Islam", DeepResearchDepth.QUICK, false)).subscribe(sub);
            events = sub.awaitCompletion(Duration.ofSeconds(10));
        }

        assertEquals(List.of(ProgressChunk.class, ProgressChunk.class, TokenChunk.class, ReportChunk.class, DoneChunk.class),
                events.stream().map(Object::getClass).toList());
        assertNull(events.getFirst().model(), "the example's first progress event carries model: null");
        assertEquals("Analyzing topic and planning report structure...", ((ProgressChunk) events.getFirst()).message().en());
        assertEquals("يؤكد ", ((TokenChunk) events.get(2)).choices().getFirst().content());

        DeepResearchReport report = ((ReportChunk) events.get(3)).report();
        assertEquals("أهمية طلب العلم في الإسلام", report.title());
        assertEquals("[إسلام ويب:228949]", report.sections().getFirst().citedSources().getFirst().citationTag());
        assertEquals(Boolean.TRUE, report.sources().getFirst().wasCited());

        DoneChunk done = (DoneChunk) events.get(4);
        assertEquals("quick", done.metadata().get("depth"));
        assertEquals(267.32, ((Number) done.metadata().get("elapsed_seconds")).doubleValue());
        assertEquals("stop", done.choices().getFirst().finishReason());

        String body = server.lastReceived().bodyAsString();
        assertTrue(body.startsWith("{\"stream\":true,"), body);
        assertTrue(body.contains("\"depth\":\"quick\"") && body.contains("\"web_search\":false"), body);
        assertEquals(1, server.hits());
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("the blocking variant returns the example's report")
    void theBlockingVariantReturnsTheReport(FanarJsonCodec codec) {
        server.enqueue(Reply.sse(SPEC_EXAMPLE));
        try (FanarClient client = client(codec)) {
            DeepResearchReport report = client.sadiq().deepResearch(DeepResearchRequest.of(ChatModel.FANAR_SADIQ_2, "topic"));
            assertEquals("أهمية طلب العلم في الإسلام", report.title());
            assertEquals(1, report.sections().size());
        }
        assertEquals(1, server.hits());
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("an in-stream error envelope is the typed exception, stream and blocking alike")
    void anInStreamErrorEnvelopeIsTyped(FanarJsonCodec codec) throws Exception {
        server.enqueue(Reply.sse(FAILED_RUN), Reply.sse(FAILED_RUN));
        CollectingSubscriber<DeepResearchEvent> sub = CollectingSubscriber.unbounded();
        try (FanarClient client = client(codec)) {
            client.sadiq().deepResearchStream(DeepResearchRequest.of(ChatModel.FANAR_SADIQ_2, "topic")).subscribe(sub);
            FanarInternalServerException streamed = assertInstanceOf(FanarInternalServerException.class,
                    sub.awaitError(Duration.ofSeconds(10)));
            assertEquals("the run blew up", streamed.getMessage());

            FanarInternalServerException blocking = assertThrows(FanarInternalServerException.class,
                    () -> client.sadiq().deepResearch(DeepResearchRequest.of(ChatModel.FANAR_SADIQ_2, "topic")));
            assertEquals("the run blew up", blocking.getMessage());
        }
        assertEquals(2, server.hits());
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("a terminal frame without metadata or usage, and without a model, is still the DoneChunk")
    void aBareTerminalFrameIsTheDoneChunk(FanarJsonCodec codec) throws Exception {
        server.enqueue(Reply.sse(BARE_TERMINAL_RUN));
        CollectingSubscriber<DeepResearchEvent> sub = CollectingSubscriber.unbounded();
        try (FanarClient client = client(codec)) {
            client.sadiq().deepResearchStream(DeepResearchRequest.of(ChatModel.FANAR_SADIQ_2, "topic")).subscribe(sub);
            List<DeepResearchEvent> events = sub.awaitCompletion(Duration.ofSeconds(10));
            assertEquals(List.of(ReportChunk.class, DoneChunk.class), events.stream().map(Object::getClass).toList());
            assertNull(events.get(1).model());
            assertTrue(((DoneChunk) events.get(1)).metadata().isEmpty());
        }
        assertEquals(1, server.hits());
    }

    private FanarClient client(FanarJsonCodec codec) {
        return FanarClient.builder()
                .apiKey("sk_test")
                .baseUrl(server.baseUri())
                .jsonCodec(codec)
                .retryPolicy(RetryPolicy.disabled())
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(5))
                .build();
    }
}
