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
import qa.fanar.core.FanarNotFoundException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.chat.ChatModel;
import qa.fanar.core.chat.DoneChunk;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.TokenChunk;
import qa.fanar.core.sadiq.CancelSource;
import qa.fanar.core.sadiq.DeepResearchDepth;
import qa.fanar.core.sadiq.DeepResearchEvent;
import qa.fanar.core.sadiq.DeepResearchJob;
import qa.fanar.core.sadiq.DeepResearchRequest;
import qa.fanar.core.sadiq.IncompleteReason;
import qa.fanar.core.sadiq.JobStatus;
import qa.fanar.core.sadiq.ReportChunk;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.json.jackson2.Jackson2FanarJsonCodec;
import qa.fanar.json.jackson3.Jackson3FanarJsonCodec;
import qa.fanar.testsupport.CollectingSubscriber;
import qa.fanar.testsupport.ScriptedHttpServer;
import qa.fanar.testsupport.ScriptedHttpServer.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spec's own deep-research job examples, decoded end to end by the <em>real</em> JSON adapters:
 * {@code FanarClient.builder()} with Jackson 2 and with Jackson 3 → {@code sadiq()}'s job operations →
 * interceptor chain → JDK transport → a scripted server replaying the 2026-10 spec's examples. Core's
 * seam test proves the wiring with a hand-rolled codec; this class is where the job records, the
 * request echo, the role-only delta and the job stream's resume meet the wire as shipped (ADR-032).
 * The examples are the spec's, with their literal {@code ...} placeholders trimmed to valid JSON.
 * Offline, no key needed.
 */
@Tag("integration")
class DeepResearchJobWireIntegrationTest {

    private static final String JOB_ID = "5bbed6e2-ae53-486f-823a-2f01992ece59";

    /** The POST's `job` example: a background start, queued. */
    private static final String QUEUED = """
            {"id": "c3869c31-4283-4934-b7d6-18ad35265d70", "object": "job", "type": "deep_research", "status": "queued",
             "progress_pct": 0, "attempt": 0, "created_at": 1791099697, "updated_at": 1791099697,
             "request": {"model": "Fanar-Sadiq-2", "input": "ما أهمية طلب العلم في الإسلام؟", "depth": "quick", "web_search": false}}
            """;

    /** The GET's `completed` example, with its report trimmed to one section and one source. */
    private static final String COMPLETED = """
            {"id": "5bbed6e2-ae53-486f-823a-2f01992ece59", "object": "job", "type": "deep_research", "status": "completed",
             "progress_pct": 100, "attempt": 1, "created_at": 1791099046, "started_at": 1791099046,
             "completed_at": 1791099575, "updated_at": 1791099575,
             "request": {"model": "Fanar-Sadiq-2", "input": "ما فضل الصدقة في الإسلام؟", "depth": "quick", "web_search": false},
             "result": {"topic": "ما فضل الصدقة في الإسلام؟", "language": "ar", "title": "فضل الصدقة في الإسلام",
                        "title_ar": "فضل الصدقة في الإسلام", "overview": "**المقدمة:**",
                        "sections": [{"heading": "الأهمية الروحية والدينية للصدقة", "heading_ar": "الأهمية الروحية والدينية للصدقة",
                                      "content": "## الأهمية الروحية", "mode": "general",
                                      "sources": [{"quote": "فنزلت", "source": "نضرة النعيم [3243]", "was_cited": true, "citation_tag": "[الشاملة:3243]"}],
                                      "cited_sources": [{"quote": "فنزلت", "source": "نضرة النعيم [3243]", "was_cited": true, "citation_tag": "[الشاملة:3243]"}]}],
                        "sources": [{"quote": "للصدقة شأن عظيم", "source": "https://islamweb.net/ar/article/42149/", "was_cited": true, "citation_tag": "[إسلام ويب:42149]"}],
                        "metadata": {"depth": "quick", "total_queries": 5, "elapsed_seconds": 468.65, "web_search_used": false},
                        "markdown": "# فضل الصدقة في الإسلام"}}
            """;

    /** The GET's `incomplete` example: stopped by a content filter, no result. */
    private static final String INCOMPLETE = """
            {"id": "fda0a674-7494-480d-83ea-0cf12f31dc83", "object": "job", "type": "deep_research", "status": "incomplete",
             "incomplete_details": {"reason": "content_filter"}, "progress_pct": 50, "attempt": 1,
             "created_at": 1791100381, "started_at": 1791100381, "completed_at": 1791100692, "updated_at": 1791100692,
             "request": {"model": "Fanar-Sadiq-2", "input": "ما أهمية الصلاة في حياة المسلم؟", "depth": "quick", "web_search": false}}
            """;

    /** The cancel's `cancelled` example. */
    private static final String CANCELLED = """
            {"id": "c3869c31-4283-4934-b7d6-18ad35265d70", "object": "job", "type": "deep_research", "status": "cancelled",
             "progress_pct": 0, "attempt": 0, "created_at": 1791099697, "completed_at": 1791099697, "updated_at": 1791099697,
             "cancel_source": "owner",
             "request": {"model": "Fanar-Sadiq-2", "input": "ما أهمية طلب العلم في الإسلام؟", "depth": "quick", "web_search": false}}
            """;

    /** The GET's `from_start` stream example, up to the draft: the server ends the response here. */
    private static final String FROM_START = """
            id: 1791099047115-0
            data: {"id": "chatcmpl-7d72ef6259e8459cb56ae483", "object": "chat.completion.chunk", "created": 1791099047, "model": null, "progress": {"message": {"ar": "جاري تحليل الموضوع وتخطيط هيكل التقرير...", "en": "Analyzing topic and planning report structure..."}}}

            id: 1791099077710-0
            data: {"id": "chatcmpl-1791099046", "object": "chat.completion.chunk", "created": 1791099046, "model": "Fanar-Sadiq-2", "choices": [{"index": 0, "delta": {"role": "assistant"}, "finish_reason": null}]}

            id: 1791099077711-0
            data: {"id": "chatcmpl-1791099046", "object": "chat.completion.chunk", "created": 1791099046, "model": "Fanar-Sadiq-2", "choices": [{"index": 0, "delta": {"content": "# فضل الصدقة في الإسلام"}, "finish_reason": null}]}

            """;

    /** The `resumed` example — what `starting_after=1791099077711-0` returns — ending on a keepalive, no `[DONE]`. */
    private static final String RESUMED = """
            id: 1791099077744-0
            data: {"id": "chatcmpl-1791099046", "object": "chat.completion.chunk", "created": 1791099046, "model": "Fanar-Sadiq-2", "progress": {"message": {"ar": "اكتملت النظرة العامة", "en": "Overview complete"}}}

            id: 1791099077745-0
            data: {"id": "chatcmpl-1791099046", "object": "chat.completion.chunk", "created": 1791099046, "model": "Fanar-Sadiq-2", "progress": {"message": {"ar": "جاري البحث في الأقسام الأولية (2 من 4)...", "en": "Researching initial sections (2 of 4)..."}}}

            : keepalive

            """;

    /** The rest of `from_start`: the report, the terminal frame with its references, `[DONE]` with its id. */
    private static final String TO_DONE = """
            id: 1791099575053-0
            data: {"id": "chatcmpl-1791099046", "object": "chat.completion.chunk", "created": 1791099046, "model": "Fanar-Sadiq-2", "report": {"title": "فضل الصدقة في الإسلام", "sections": [], "sources": []}}

            id: 1791099575097-0
            data: {"id": "chatcmpl-1791099046", "object": "chat.completion.chunk", "created": 1791099046, "model": "Fanar-Sadiq-2", "choices": [{"index": 0, "delta": {"references": [{"index": 0, "number": 1, "source": "https://fiqh.islamonline.net/", "content": "أدلة التكافل الاجتماعي في الإسلام", "citation_tag": "[إسلام أون لاين:518085]", "was_cited": true}]}, "finish_reason": "stop"}], "metadata": {"depth": "quick", "elapsed_seconds": 468.65, "total_sources": 24, "web_search_used": false}}

            id: 1791099575102-0
            data: [DONE]

            """;

    @AutoClose
    private final ScriptedHttpServer server = ScriptedHttpServer.start();

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(Named.of("jackson2", new Jackson2FanarJsonCodec())),
                Arguments.of(Named.of("jackson3", new Jackson3FanarJsonCodec())));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("the spec's job examples decode: queued on start, completed with its report, incomplete, cancelled")
    void theSpecJobExamplesDecode(FanarJsonCodec codec) throws Exception {
        server.enqueue(Reply.json(200, QUEUED), Reply.json(200, COMPLETED), Reply.json(200, INCOMPLETE),
                Reply.json(200, CANCELLED));
        DeepResearchJob queued;
        DeepResearchJob completed;
        DeepResearchJob incomplete;
        DeepResearchJob cancelled;
        try (FanarClient client = client(codec)) {
            queued = client.sadiq().startDeepResearch(new DeepResearchRequest(
                    ChatModel.FANAR_SADIQ_2, "ما أهمية طلب العلم في الإسلام؟", DeepResearchDepth.QUICK, null));
            completed = client.sadiq().retrieveDeepResearchJob(JOB_ID);
            incomplete = client.sadiq().retrieveDeepResearchJob("fda0a674-7494-480d-83ea-0cf12f31dc83");
            cancelled = client.sadiq().cancelDeepResearchJob("c3869c31-4283-4934-b7d6-18ad35265d70");
        }

        String body = server.received().getFirst().bodyAsString();
        assertTrue(body.startsWith("{\"stream\":false,\"background\":true,"), body);
        assertTrue(body.contains("\"depth\":\"quick\"") && !body.contains("web_search"), body);

        assertEquals(JobStatus.QUEUED, queued.status());
        assertNull(queued.startedAt());
        assertEquals(new DeepResearchRequest(ChatModel.FANAR_SADIQ_2, "ما أهمية طلب العلم في الإسلام؟",
                DeepResearchDepth.QUICK, false), queued.request(), "the echo carries the server's web-search default");

        assertEquals(JobStatus.COMPLETED, completed.status());
        assertTrue(completed.status().isTerminal());
        assertEquals(100, completed.progressPct());
        assertEquals(1_791_099_575L, completed.completedAt());
        assertEquals("فضل الصدقة في الإسلام", completed.result().title());
        assertEquals("[الشاملة:3243]", completed.result().sections().getFirst().citedSources().getFirst().citationTag());
        assertEquals(468.65, ((Number) completed.result().metadata().get("elapsed_seconds")).doubleValue());

        assertEquals(IncompleteReason.CONTENT_FILTER, incomplete.incompleteDetails().reason());
        assertNull(incomplete.result());

        assertEquals(JobStatus.CANCELLED, cancelled.status());
        assertEquals(CancelSource.OWNER, cancelled.cancelSource());
        assertEquals(4, server.hits());
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("the spec's job stream decodes across two resumes, the second after a keepalive-only tail")
    void theSpecJobStreamDecodesAcrossAResume(FanarJsonCodec codec) throws Exception {
        server.enqueue(Reply.sse(FROM_START), Reply.sse(RESUMED), Reply.sse(TO_DONE));
        CollectingSubscriber<DeepResearchEvent> sub = CollectingSubscriber.unbounded();
        List<DeepResearchEvent> events;
        try (FanarClient client = client(codec)) {
            client.sadiq().deepResearchJobStream(JOB_ID).subscribe(sub);
            events = sub.awaitCompletion(Duration.ofSeconds(10));
        }

        assertEquals(List.of(ProgressChunk.class, TokenChunk.class, TokenChunk.class, ProgressChunk.class,
                ProgressChunk.class, ReportChunk.class, DoneChunk.class), events.stream().map(Object::getClass).toList());
        assertNull(events.getFirst().model(), "the first progress event carries model: null");
        assertEquals("", ((TokenChunk) events.get(1)).choices().getFirst().content(),
                "a role-only delta decodes as a token without content");
        assertEquals("# فضل الصدقة في الإسلام", ((TokenChunk) events.get(2)).choices().getFirst().content());
        assertEquals("Overview complete", ((ProgressChunk) events.get(3)).message().en());
        assertEquals("فضل الصدقة في الإسلام", ((ReportChunk) events.get(5)).report().title());
        DoneChunk done = (DoneChunk) events.get(6);
        assertEquals("https://fiqh.islamonline.net/", done.choices().getFirst().references().getFirst().source(),
                "a reference with members beyond the schema's still decodes");
        assertEquals("quick", done.metadata().get("depth"));

        assertEquals("stream=true", server.received().get(0).query());
        assertEquals("stream=true&starting_after=1791099077711-0", server.received().get(1).query(),
                "the spec's own resumed request");
        assertEquals("stream=true&starting_after=1791099077745-0", server.received().get(2).query());
        assertEquals(3, server.hits());
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("codecs")
    @DisplayName("an unknown job's 404 envelope routes to FanarNotFoundException with the server's message")
    void anUnknownJobIsTyped(FanarJsonCodec codec) {
        server.enqueue(Reply.json(404, "{\"error\": {\"code\": \"Not found\", \"message\": \"No job found with this id.\", \"status\": 404}}"));
        try (FanarClient client = client(codec)) {
            FanarNotFoundException e = assertThrows(FanarNotFoundException.class,
                    () -> client.sadiq().retrieveDeepResearchJob("unknown"));
            assertEquals("No job found with this id.", e.getMessage());
        }
        assertEquals("/v1/sadiq/deep-research/unknown", server.lastReceived().path());
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
